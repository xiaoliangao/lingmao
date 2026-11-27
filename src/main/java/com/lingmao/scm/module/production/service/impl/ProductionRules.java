package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.production.dto.NodeDot;
import com.lingmao.scm.module.production.entity.MaterialIssueStatus;
import com.lingmao.scm.module.production.entity.ProductionNode;
import com.lingmao.scm.module.production.entity.ProductionOrderItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.CLOSED;
import static com.lingmao.scm.common.doc.DocStatus.DONE;
import static com.lingmao.scm.common.doc.DocStatus.DRAFT;
import static com.lingmao.scm.common.doc.DocStatus.PENDING;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;
import static com.lingmao.scm.common.doc.DocStatus.VOIDED;

/**
 * 生产单的规则（docs/api/05 §2、§3.3.1）：状态转换表、节点倒排和状态、溢短装、领料状态。都是纯函数，单测直接测。
 * 阈值一期写死（PRD §12 默认，数据库设计 §3.9），甲方要在页面改时再挪进 sys_config
 */
final class ProductionRules {

    /**
     * 状态转换表（§2.1）。report、editPlan、reportIssue、receive、cut、issue、returnMaterial 不改状态（报进度、登记回货、登记裁床数另算），
     * 写在这里是为了出按钮、查能不能做；audit 中间一级审过还是待审核；reopen 按进度回到已下单、生产中或已完成
     */
    static final StatusFlow<DocStatus> FLOW = StatusFlow.of(DocStatus.class)
            .on("edit", DRAFT).to(DRAFT)
            .on("remove", DRAFT).to(DRAFT)
            .on("submit", DRAFT).to(PENDING)
            .on("withdraw", PENDING).to(DRAFT)
            .on("reject", PENDING).to(DRAFT)
            .on("audit", PENDING).to(PENDING, APPROVED)
            .on("unaudit", APPROVED).to(DRAFT)
            .on("report", APPROVED, RUNNING).to(APPROVED, RUNNING)
            .on("editPlan", APPROVED, RUNNING).to(APPROVED, RUNNING)
            .on("reportIssue", APPROVED, RUNNING).to(APPROVED, RUNNING)
            .on("receive", APPROVED, RUNNING, DONE).to(RUNNING, DONE)
            .on("cut", APPROVED, RUNNING).to(APPROVED, RUNNING, DONE)
            .on("issue", APPROVED, RUNNING).to(APPROVED, RUNNING)
            .on("returnMaterial", APPROVED, RUNNING, DONE, CLOSED).to(APPROVED, RUNNING, DONE, CLOSED)
            .on("close", APPROVED, RUNNING, DONE).to(CLOSED)
            .on("reopen", CLOSED).to(APPROVED, RUNNING, DONE)
            .on("void", DRAFT, PENDING).to(VOIDED)
            .build();

    static final String PERM = "production:order:";

    /** 和前端 types/production.ts 的 PRODUCTION_STATUS 一样（部分回货不是单独的状态） */
    static final StatusLabels<DocStatus> STATUS = StatusLabels.DOC.rename(APPROVED, "已下单").rename(RUNNING, "生产中");

    /** 回货单只用三个状态（RECEIPT_STATUS） */
    static final StatusLabels<DocStatus> RECEIPT_STATUS = StatusLabels.DOC.rename(APPROVED, "已登记");

    /** 货期预警、报进度、上报异常只看这两个状态 */
    static final Set<DocStatus> LIVE = Set.of(APPROVED, RUNNING);

    /** 溢装 %（甲方 11-18 答 Q09）：每个 SKU 排产不超过 ⌊订单件数 × 1.05⌋，回货的良品不超过 ⌊件数 × 1.05⌋，登记了裁床数按裁床数 */
    static final BigDecimal OVER_PCT = BigDecimal.valueOf(5);

    /** 短装 %（Q09，甲方没说，照默认）：累计回货的良品 ≥ 总件数（登记了裁床数按裁床数）× 97% 算完成 */
    static final int SHORT_PCT = 3;

    /** 交期还剩几天算临期 */
    static final int NEAR_DAYS = 7;

    /** 节点计划完成前几天算临期 */
    static final int NODE_WARN_DAYS = 2;

    /** 字典 prod_node 的"裁剪"：它的计划开始是要料日期（03 的 NeedDateSource） */
    static final String CUT_NODE = "CUT";

    /** 字典 prod_node 某项没填天数时按 1 天 */
    static final int DEFAULT_NODE_DAYS = 1;

    private ProductionRules() {
    }

    /** 一个 SKU 最多排到 / 回到多少件 */
    static int maxPieces(int qty) {
        return Num.maxPieces(qty, OVER_PCT);
    }

    /** 回货按多少件算（RFC 013 §4）：登记了裁床数按裁床数，没登记按件数 */
    static int basis(ProductionOrderItem item) {
        return item.getCutQty() != null ? item.getCutQty() : item.getQty();
    }

    /** 良品 = 已回 − 次品。回货上限、回没回齐都只看良品（甲方 11-19）：次品退回去返修，补回来的照样能登记 */
    static int good(ProductionOrderItem item) {
        return item.getReturnedQty() - item.getDefectQty();
    }

    /** 一个 SKU 最多回到多少件良品：⌊件数或裁床数 × 1.05⌋ */
    static int maxReturn(ProductionOrderItem item) {
        return maxPieces(basis(item));
    }

    /** 已经回了 good 件良品时裁床数最少多少：⌊裁床数 × 1.05⌋ 不能小于已回的良品 */
    static int minCut(int good) {
        int cut = (int) ((long) good * 100 / (100 + OVER_PCT.intValue()));
        while (maxPieces(cut) < good) {
            cut++;
        }
        return cut;
    }

    /** 累计回货的良品够不够算完成：≥ 总件数 × 97% */
    static boolean finished(int totalQty, int goodQty) {
        return totalQty > 0 && (long) goodQty * 100 >= (long) totalQty * (100 - SHORT_PCT);
    }

    /**
     * 按进度算状态（§2.1）：良品回够 97%（件数，登记了裁床数的 SKU 按裁床数）已完成；回过货（次品也算）、或者有节点报过开始是生产中；都没有是已下单。
     * 登记回货、作废回货单、报进度、取消结案都按它
     */
    static DocStatus byProgress(List<ProductionOrderItem> items, List<ProductionNode> nodes) {
        int total = items.stream().mapToInt(ProductionRules::basis).sum();
        int returned = items.stream().mapToInt(ProductionOrderItem::getReturnedQty).sum();
        if (finished(total, items.stream().mapToInt(ProductionRules::good).sum())) {
            return DONE;
        }
        return returned > 0 || nodes.stream().anyMatch(n -> n.getActualStart() != null) ? RUNNING : APPROVED;
    }

    /** 字典 prod_node 的一项：启用的才生成 */
    record NodeDef(String code, String name, int days) {
    }

    /** 倒排出来的一个节点的计划 */
    record Plan(NodeDef def, LocalDate start, LocalDate finish) {
    }

    /**
     * 从交期往前倒排（§3.3.1）：最后一个节点计划完成 = 交期，计划开始 = 计划完成 −（天数 − 1）；
     * 前一个节点的计划完成 = 后一个的计划开始 − 1 天
     */
    static List<Plan> backSchedule(List<NodeDef> defs, LocalDate dueDate) {
        List<Plan> out = new ArrayList<>();
        LocalDate finish = dueDate;
        for (int i = defs.size() - 1; i >= 0; i--) {
            NodeDef d = defs.get(i);
            LocalDate start = finish.minusDays(Math.max(d.days(), 1) - 1L);
            out.addFirst(new Plan(d, start, finish));
            finish = start.minusDays(1);
        }
        return out;
    }

    /**
     * 节点状态（PRD §6.7.2）：报了完成的，晚于计划完成是 LATE、不然 DONE；没完成的，今天过了计划完成 LATE，
     * 离计划完成 ≤ 2 天 WARN，报过开始 RUNNING，其他 TODO
     */
    static String nodeState(ProductionNode n, LocalDate today) {
        if (n.getActualFinish() != null) {
            return n.getActualFinish().isAfter(n.getPlanFinish()) ? "LATE" : "DONE";
        }
        if (today.isAfter(n.getPlanFinish())) {
            return "LATE";
        }
        if (ChronoUnit.DAYS.between(today, n.getPlanFinish()) <= NODE_WARN_DAYS) {
            return "WARN";
        }
        return n.getActualStart() != null ? "RUNNING" : "TODO";
    }

    /** 晚了几天：完成的 = 实际完成 − 计划完成，没完成的 = 今天 − 计划完成；不是 LATE 是 null */
    static Integer lateDays(ProductionNode n, LocalDate today) {
        if (!"LATE".equals(nodeState(n, today))) {
            return null;
        }
        LocalDate end = n.getActualFinish() != null ? n.getActualFinish() : today;
        return (int) ChronoUnit.DAYS.between(n.getPlanFinish(), end);
    }

    static List<NodeDot> dots(List<ProductionNode> nodes, LocalDate today) {
        return nodes.stream().map(n -> new NodeDot(n.getNodeName(), nodeState(n, today))).toList();
    }

    /** 第一个没报完成的节点；都完成了、没节点是 null */
    static String currentNodeName(List<ProductionNode> nodes) {
        return nodes.stream().filter(n -> n.getActualFinish() == null).map(ProductionNode::getNodeName).findFirst().orElse(null);
    }

    /** 预计交期 ?? 交期 − 今天；只有已下单、生产中的有 */
    static Integer daysLeft(DocStatus status, LocalDate dueDate, LocalDate expectDate, LocalDate today) {
        if (!LIVE.contains(status)) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(today, expectDate != null ? expectDate : dueDate);
    }

    /**
     * 领料状态：need 是应领（物料 SKU → 库存单位），issued 是已领。没有应领的行（资料包里没有自购的料）是 null；
     * 每种都领够了 DONE，一种都没领过 NONE，其他 PARTIAL
     */
    static MaterialIssueStatus issueStatus(Map<Long, BigDecimal> need, Map<Long, BigDecimal> issued) {
        if (need.isEmpty()) {
            return null;
        }
        boolean all = need.entrySet().stream()
                .allMatch(e -> issued.getOrDefault(e.getKey(), BigDecimal.ZERO).compareTo(e.getValue()) >= 0);
        if (all) {
            return MaterialIssueStatus.DONE;
        }
        boolean any = issued.values().stream().anyMatch(q -> q.signum() > 0);
        return any ? MaterialIssueStatus.PARTIAL : MaterialIssueStatus.NONE;
    }
}
