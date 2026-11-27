package com.lingmao.scm.module.settle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.settle.dto.PendingStatementQuery;
import com.lingmao.scm.module.settle.dto.PendingStatementRow;
import com.lingmao.scm.module.settle.dto.StatementQuery;
import com.lingmao.scm.module.settle.dto.StatementRowVO;
import com.lingmao.scm.module.settle.entity.Statement;
import com.lingmao.scm.module.settle.entity.StatementItem;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 对账单（RFC 019 §5.1）。拉单据的几条查询只读 JOIN 入库单、回货单、生产单、物料（架构 §3.2 规则 4），
 * "还没进对账单"按 stl_statement_item 的 active 行判断（和唯一索引一个口径）
 */
@Mapper
public interface StatementMapper extends BaseMapper<Statement> {

    /** 列表（XML） */
    IPage<StatementRowVO> selectRowPage(IPage<StatementRowVO> page, @Param("q") StatementQuery query, @Param("scope") DataScope scope);

    /** 单头的显示字段（XML）：主体名、供应商名、明细几行 */
    StatementRowVO selectRow(@Param("id") long id);

    /** 改明细、动作前锁住单头。@ResultMap 用实体的那份：比对结果（JSONB）才读得出来 */
    @Select("SELECT * FROM stl_statement WHERE id = #{id} FOR UPDATE")
    @ResultMap("mybatis-plus_Statement")
    Statement lockById(@Param("id") long id);

    /** 待对账（XML）：按主体 × 供应商 × 月份合计还没进对账单的单据 */
    List<PendingStatementRow> selectPending(@Param("q") PendingStatementQuery query);

    /** 这段时间里这家还没进对账单的采购入库、采购退货明细（XML）；数量、金额都是正的，退货由 Service 记负数 */
    List<StatementItem> selectPurchaseLines(@Param("companyId") long companyId, @Param("partnerId") long partnerId,
                                            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 这段时间里这家（加工厂）还没进对账单的回货单（XML）：一张一行，qty 是件数，deductAmount 先放次品数，Service 再乘单价 */
    List<StatementItem> selectReceipts(@Param("companyId") long companyId, @Param("partnerId") long partnerId,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 这段时间里这家还没进对账单的扣款登记（XML） */
    List<StatementItem> selectDeductions(@Param("companyId") long companyId, @Param("partnerId") long partnerId,
                                         @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 明细变了：重算合计，清掉比对结果，版本号 +1 */
    @Update("UPDATE stl_statement s SET total_amount = t.amount, deduct_amount = t.deduct, payable_amount = t.amount - t.deduct, "
            + "their_amount = NULL, compare_result = NULL, version = s.version + 1, update_by = #{userId}, update_time = now() "
            + "FROM (SELECT COALESCE(SUM(amount), 0) AS amount, COALESCE(SUM(deduct_amount), 0) AS deduct "
            + "      FROM stl_statement_item WHERE statement_id = #{id} AND active) t "
            + "WHERE s.id = #{id}")
    int recompute(@Param("id") long id, @Param("userId") Long userId);

    /** 存比对结果（对方合计、逐处结果），版本号 +1；状态、版本号对不上改 0 行 */
    @Update("UPDATE stl_statement SET their_amount = #{theirAmount}, compare_result = CAST(#{json} AS jsonb), version = version + 1, "
            + "update_by = #{userId}, update_time = now() "
            + "WHERE id = #{id} AND version = #{version} AND status IN ('DRAFT', 'SENT')")
    int saveCompare(@Param("id") long id, @Param("version") int version, @Param("theirAmount") java.math.BigDecimal theirAmount,
                    @Param("json") String json, @Param("userId") Long userId);

    /** 引用查询：进了没作废的对账单的入库单、退货单、回货单 → 对账单 */
    @Select("<script>SELECT s.* FROM stl_statement s WHERE EXISTS (SELECT 1 FROM stl_statement_item i "
            + "WHERE i.statement_id = s.id AND i.active AND i.source_id = #{sourceId} AND i.source_type IN "
            + "<foreach collection='types' item='t' open='(' separator=',' close=')'>#{t}</foreach>) ORDER BY s.id</script>")
    @ResultMap("mybatis-plus_Statement")
    List<Statement> selectBySource(@Param("types") Collection<String> types, @Param("sourceId") long sourceId);

    @Select("<script>SELECT * FROM stl_statement WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    @ResultMap("mybatis-plus_Statement")
    List<Statement> selectByIdList(@Param("ids") Collection<Long> ids);
}
