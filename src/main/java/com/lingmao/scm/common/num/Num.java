package com.lingmao.scm.common.num;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 数量、单价、金额的取整（架构 §6、PRD §5.3、数据库设计 §1.1）。规则只写在这里，各域不自己 setScale，单测用 PRD 的例子（NumTest）。
 * <ul>
 *   <li>金额 2 位、单价 4 位、物料数量 3 位，都是四舍五入；成品件数是整数</li>
 *   <li>建议采购量向上取整到最小包装量的倍数，且不小于起订量（PRD §6.6.1）</li>
 *   <li>上限（超排、超收、超回货）向下取整：件数到整数，物料到 3 位（PRD §6.5.5、§6.7.5，Q09）</li>
 * </ul>
 * 中间结果不取整，到最后一步再取：先取整再乘会把误差放大（单价 × 件数时单价已经是 4 位，不要先取成 2 位）。
 * 传 null 的约定：取整方法原样返回 null；amount 有一个是 null 就返回 null（草稿里没填单价的行没有金额，不当成 0）。
 * 百分比按库里的存法传百分数：3 表示 3%（架构 §6，列名带 _pct）。
 * <p>
 * 价税换算（含税 ↔ 不含税）PRD 一期没定：单据上记"单价 + 税率 + 是否含税"，金额就是数量 × 单价，不折算；
 * 对账结算（M11）要折算时再加到这里。
 */
public final class Num {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 金额：2 位 */
    public static final int MONEY_SCALE = 2;

    /** 单价：4 位 */
    public static final int PRICE_SCALE = 4;

    /** 物料数量：3 位（米、码、公斤） */
    public static final int QTY_SCALE = 3;

    private Num() {
    }

    /** 金额四舍五入到 2 位 */
    public static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 单价四舍五入到 4 位 */
    public static BigDecimal price(BigDecimal value) {
        return value == null ? null : value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /** 物料数量四舍五入到 3 位：算料的毛需求、需求，单件用量（6 位）乘出来的数都按它取（数据库设计 §1.1） */
    public static BigDecimal qty(BigDecimal value) {
        return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    /** 金额 = 数量 × 单价，四舍五入到 2 位。采购单、物料出入库单的行金额 */
    public static BigDecimal amount(BigDecimal qty, BigDecimal price) {
        return qty == null || price == null ? null : money(qty.multiply(price));
    }

    /** 金额 = 件数 × 单价：需求订单、生产单的行金额（件数是整数） */
    public static BigDecimal amount(Integer pieces, BigDecimal price) {
        return pieces == null ? null : amount(BigDecimal.valueOf(pieces), price);
    }

    /**
     * value × (1 + pct%)，不取整：毛需求加损耗（损耗 3% → × 1.03）、需求加备料比、上限加溢装比例。
     * 百分比是两位小数，乘出来是有限小数，留给最后一步取整。pct 是 null 当 0
     */
    public static BigDecimal addPct(BigDecimal value, BigDecimal pct) {
        if (value == null || pct == null || pct.signum() == 0) {
            return value;
        }
        return value.multiply(HUNDRED.add(pct)).divide(HUNDRED);
    }

    /**
     * 建议采购量（PRD §6.6.1，采购单位）：净需求 ÷ 换算系数，向上取整到最小包装量的倍数，且不小于起订量；净需求 ≤ 0 时为 0。
     * <ul>
     *   <li>净需求按库存单位；换算系数是"1 采购单位 = n 库存单位"，null 当 1</li>
     *   <li>除出来先按数量的 3 位四舍五入，再向上取整：系数存的是 6 位小数，1/3 存成 0.333333，
     *       1 ÷ 0.333333 = 3.000003，不先取一下就会被进成 4</li>
     *   <li>最小包装量 null 或 ≤ 0 当 1（物料档案默认 1）；起订量 null 当没有</li>
     * </ul>
     * PRD §4.3 的例子：净需求 870.86 米、系数 1、最小包装 1、起订 100 → 871
     */
    public static BigDecimal suggestPurchase(BigDecimal netNeed, BigDecimal ratio, BigDecimal minPack, BigDecimal moq) {
        if (netNeed == null || netNeed.signum() <= 0) {
            return BigDecimal.ZERO.setScale(QTY_SCALE);
        }
        BigDecimal perUnit = ratio == null || ratio.signum() <= 0 ? BigDecimal.ONE : ratio;
        BigDecimal pack = minPack == null || minPack.signum() <= 0 ? BigDecimal.ONE : minPack;
        BigDecimal inPurchaseUnit = netNeed.divide(perUnit, QTY_SCALE, RoundingMode.HALF_UP);
        // 先跟起订量取大，再按最小包装往上取整：要 1、起订 10、一包 6 → 12，不是不成整包的 10
        BigDecimal required = moq == null ? inPurchaseUnit : inPurchaseUnit.max(moq);
        BigDecimal packed = required.divide(pack, 0, RoundingMode.CEILING).multiply(pack);
        return qty(packed);
    }

    /**
     * 件数上限 = ⌊件数 × (1 + 溢装%)⌋，向下取整（Q09）：排单不能超过订单数的上限、回货不能超过生产单数的上限。
     * 订单 120 件、溢装 3% → 123（123.6 往下取）；不能突破 INTEGER 件数的存储上限。
     */
    public static int maxPieces(int pieces, BigDecimal overPct) {
        return addPct(BigDecimal.valueOf(pieces), overPct).setScale(0, RoundingMode.FLOOR)
                .min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValueExact();
    }

    /** 物料数量上限 = 数量 × (1 + 超收%)，向下取到 3 位：采购入库的超收上限（默认 5%） */
    public static BigDecimal maxQty(BigDecimal qty, BigDecimal overPct) {
        return qty == null ? null : addPct(qty, overPct).setScale(QTY_SCALE, RoundingMode.FLOOR);
    }

    /** 倍率 = a ÷ b，两位小数四舍五入（核价的吊牌价 ÷ 单件成本，架构 §4.9）；有一个是 null 或 b 是 0 时返回 null，页面不显示 */
    public static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        if (a == null || b == null || b.signum() == 0) {
            return null;
        }
        return a.divide(b, MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
