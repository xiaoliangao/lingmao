package com.lingmao.scm.module.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.purchase.dto.MrpCalcQuery;
import com.lingmao.scm.module.purchase.dto.MrpCalcRowVO;
import com.lingmao.scm.module.purchase.dto.MrpOrderOptionVO;
import com.lingmao.scm.module.purchase.entity.MrpCalc;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** 算料单（XML 在 mapper/purchase/MrpCalcMapper.xml）。订单、BOM、款号只读 JOIN（架构 §3.2 规则 4） */
@Mapper
public interface MrpCalcMapper extends BaseMapper<MrpCalc> {

    /** 改、删、动作之前锁住单头。resultMap 用实体自动生成的那个，输入快照（JSONB）才读得出来 */
    @Select("SELECT * FROM mrp_calc WHERE id = #{id} FOR UPDATE")
    @ResultMap("mybatis-plus_MrpCalc")
    MrpCalc lockById(@Param("id") long id);

    /** 几张一起锁（按 id 排，免得互相等）：采购单改数量、删除、作废要回写算料行的已转采购，先锁住算料单 */
    @Select("<script>SELECT id FROM mrp_calc WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id FOR UPDATE</script>")
    List<Long> lockByIds(@Param("ids") Collection<Long> ids);

    /**
     * 确认排队（架构 §5.4）：按物料 SKU 加事务级咨询锁，两张单同时确认、用到同一种料的，后一张等前一张提交了再算。
     * 键 = (7301 << 32) | skuId，和别处的咨询锁错开；按 SKU 从小到大加，免得互相等
     */
    @Select("<script>SELECT count(pg_advisory_xact_lock((7301::bigint &lt;&lt; 32) | k)) FROM "
            + "(SELECT k FROM unnest(ARRAY[<foreach collection='skuIds' item='s' separator=','>#{s}</foreach>]::bigint[]) k ORDER BY k) s</script>")
    long lockSkus(@Param("skuIds") Collection<Long> skuIds);

    IPage<MrpCalcRowVO> selectRowPage(IPage<MrpCalcRowVO> page, @Param("q") MrpCalcQuery query, @Param("codes") List<String> codes,
                                      @Param("scope") DataScope scope);

    record OrderHead(Long id, String no, String status, LocalDate commitDate) {
    }

    /** 订单的单号、状态、承诺交期 */
    List<OrderHead> selectOrderHeads(@Param("ids") Collection<Long> ids);

    /**
     * 订单的每个 SKU × 它锁定的生产 BOM 的每一行：件数、这个尺码的单件用量、损耗、物料来源、用哪个物料 SKU（按配料方式对颜色、尺码）。
     * 配料、分码用量没配上的 skuId、usage 是 null（资料包生效时查过，正常不会有）
     */
    record DemandRow(Long orderId, String orderNo, String styleCode, String styleSkuCode, Long techPackId, Integer bomSeq,
                     String source, Long supplierId, Long materialId, String materialCode, Long skuId, Integer qty,
                     BigDecimal usage, BigDecimal lossPct) {
    }

    List<DemandRow> selectDemand(@Param("orderIds") Collection<Long> orderIds);

    /** 订单每个 SKU 的件数、用的生产 BOM（算料单的输入快照） */
    record OrderLine(Long orderId, Long styleSkuId, Integer qty, Long techPackId) {
    }

    @Select("<script>SELECT order_id, style_sku_id, qty, tech_pack_id FROM ord_order_item WHERE qty &gt; 0 AND order_id IN "
            + "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY order_id, seq</script>")
    List<OrderLine> selectOrderLines(@Param("orderIds") Collection<Long> orderIds);

    /** "选订单算料"：已审核、生产中的需求订单，下单日期倒序 */
    IPage<MrpOrderOptionVO> selectOrderOptions(IPage<MrpOrderOptionVO> page, @Param("keyword") String keyword,
                                               @Param("codes") List<String> codes);

    /** 订单里的款号（按行号排，去重） */
    record OrderStyle(Long orderId, String styleCode) {
    }

    List<OrderStyle> selectOrderStyles(@Param("orderIds") Collection<Long> orderIds);

    /** 算料单 × 订单：单号、承诺交期 */
    record CalcOrder(Long calcId, Long orderId, String orderNo, LocalDate commitDate) {
    }

    List<CalcOrder> selectCalcOrders(@Param("calcIds") Collection<Long> calcIds);

    /** 订单算过的算料单（选订单算料的对话框、齐料、订单的下游单据） */
    record OrderCalc(Long orderId, Long calcId, String calcNo, String status, String closeReason, Long createBy) {
    }

    List<OrderCalc> selectOrderCalcs(@Param("orderIds") Collection<Long> orderIds);

    /** 重新算料出来、还没关闭的新单（草稿或已确认） */
    @Select("SELECT * FROM mrp_calc WHERE prev_calc_id = #{id} AND status IN ('DRAFT', 'CONFIRMED') ORDER BY id DESC LIMIT 1")
    @ResultMap("mybatis-plus_MrpCalc")
    MrpCalc selectNext(@Param("id") long id);

    @Insert("<script>INSERT INTO mrp_calc_order (calc_id, order_id) VALUES "
            + "<foreach collection='orderIds' item='o' separator=','>(#{calcId}, #{o})</foreach></script>")
    void insertOrders(@Param("calcId") long calcId, @Param("orderIds") Collection<Long> orderIds);

    @Delete("DELETE FROM mrp_calc_order WHERE calc_id = #{calcId}")
    void deleteOrders(@Param("calcId") long calcId);
}
