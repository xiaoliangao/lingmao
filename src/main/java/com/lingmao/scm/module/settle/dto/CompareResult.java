package com.lingmao.scm.module.settle.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.settle.entity.StatementSourceType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 和对方对账单的比对结果（RFC 019 §5.3）：存在 stl_statement.compare_result（JSONB 快照），详情接口原样返回。
 *
 * @param fileName     对方对账单的文件名（原件是对账单的附件）
 * @param comparedAt   什么时候比的
 * @param comparedBy   谁传的（用户 id）
 * @param comparedByName 谁传的（昵称快照）
 * @param sheetName    读的哪张工作表
 * @param theirRowCount 对方表里读出来几行（不算表头、合计）
 * @param theirGoods   对方的货款合计（退货是负的）
 * @param theirDeduct  对方列的扣款合计
 * @param theirTotal   对方的应付 = 货款 − 扣款
 * @param ourTotal     我方的实付（比的时候）
 * @param diff         对方 − 我方
 * @param rows         逐处结果，按日期排
 */
public record CompareResult(String fileName,
                            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai") OffsetDateTime comparedAt,
                            Long comparedBy, String comparedByName, String sheetName, int theirRowCount,
                            BigDecimal theirGoods, BigDecimal theirDeduct, BigDecimal theirTotal, BigDecimal ourTotal, BigDecimal diff,
                            List<Row> rows) {

    /** 一处的结果 */
    public enum Outcome {
        /** 一致（差 0.01 元以内） */
        MATCH,
        /** 数量或金额不一致 */
        DIFF,
        /** 对方有我方没有 */
        THEIRS_ONLY,
        /** 我方有对方没有 */
        OURS_ONLY
    }

    /**
     * @param key        这一处的编号（写说明时按它认）
     * @param outcome    结果
     * @param their      对方那边（我方有对方没有时是 null）
     * @param ours       我方那边（对方有我方没有时是 null）
     * @param diffQty    数量差（对方 − 我方），两边都有数量时才有
     * @param diffAmount 金额差（对方 − 我方）
     * @param note       说明：不一致的每处都要写才能确认
     */
    public record Row(String key, Outcome outcome, Their their, Ours ours, BigDecimal diffQty, BigDecimal diffAmount, String note) {

        public Row withNote(String value) {
            return new Row(key, outcome, their, ours, diffQty, diffAmount, value);
        }

        /** 不一致的（要写说明） */
        public boolean differs() {
            return outcome != Outcome.MATCH;
        }
    }

    /**
     * 对方的一张单据（同一个单号的几行合起来）或一行；扣款合起来一处
     *
     * @param rowNos      在对方表里是第几行（Excel 的行号）
     * @param deduct      是扣款
     */
    public record Their(List<Integer> rowNos, String no, LocalDate date, String description, BigDecimal qty, BigDecimal amount,
                        boolean deduct) {
    }

    /**
     * 我方的一张单据（对账单上同一张入库单、退货单的几行合起来）、一张回货单；扣款合起来一处
     *
     * @param sourceType 采购入库、采购退货、回货；扣款合计是 DEDUCTION
     * @param amount     比的金额：回货按对方写的是含次品还是扣了次品的，取对上的那个
     */
    public record Ours(StatementSourceType sourceType, Long sourceId, String no, String deliveryNo, LocalDate date, String description,
                       BigDecimal qty, BigDecimal amount) {
    }

    public CompareResult withRows(List<Row> value) {
        return new CompareResult(fileName, comparedAt, comparedBy, comparedByName, sheetName, theirRowCount, theirGoods, theirDeduct,
                theirTotal, ourTotal, diff, value);
    }

    /** 还没写说明的不一致处 */
    public List<Row> unexplained() {
        return rows.stream().filter(r -> r.differs() && (r.note() == null || r.note().isBlank())).toList();
    }
}
