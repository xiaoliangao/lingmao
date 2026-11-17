package com.lingmao.scm.module.production.dto;

/**
 * 货期预警（docs/api/05 §2.3），后端现算，只有已下单、生产中的单才可能为 true：
 * late 已逾期、near 临期、nodeLate 有节点延期、issue 有没关闭的异常、upstream 上游订单变了还没处理
 */
public record RiskVO(boolean late, boolean near, boolean nodeLate, boolean issue, boolean upstream) {

    public static final RiskVO NONE = new RiskVO(false, false, false, false, false);

    /** 排序分数：逾期 8、节点延期 4、有异常 2、临期 1，相加 */
    public int score() {
        return (late ? 8 : 0) + (nodeLate ? 4 : 0) + (issue ? 2 : 0) + (near ? 1 : 0);
    }
}
