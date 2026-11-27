package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.production.entity.FactoryCapacity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** 工厂产能（PRD 08.02） */
@Mapper
public interface FactoryCapacityMapper extends BaseMapper<FactoryCapacity> {

    /**
     * 按加工厂加事务级咨询锁：同一家厂的排单、改草稿、取消结案、改产能排成一队，后一个等前一个提交了再算负荷
     * （不然两人同时给同一家厂排单，都读到同样的余量，一起放行，叠起来超产）。
     * 键 = (7501 &lt;&lt; 32) | factoryId，和别处的咨询锁错开；从小到大加，免得互相等。调用方在锁完自己的单据之后再调
     */
    @Select("<script>SELECT count(pg_advisory_xact_lock((7501::bigint &lt;&lt; 32) | k)) FROM "
            + "(SELECT k FROM unnest(ARRAY[<foreach collection='ids' item='i' separator=','>#{i}</foreach>]::bigint[]) k ORDER BY k) s</script>")
    long lockFactories(@Param("ids") Collection<Long> factoryIds);

    @Select("<script>SELECT * FROM prd_factory_capacity WHERE factory_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY start_month</script>")
    List<FactoryCapacity> selectByFactories(@Param("ids") Collection<Long> factoryIds);

    /** 每家厂、每个品类在 month 当月或之前登记的最近一条（这个月用的） */
    @Select("SELECT DISTINCT ON (factory_id, COALESCE(category_id, 0)) * FROM prd_factory_capacity "
            + "WHERE start_month <= #{month} ORDER BY factory_id, COALESCE(category_id, 0), start_month DESC")
    List<FactoryCapacity> selectEffective(@Param("month") LocalDate month);

    /**
     * 加工厂手上还没做完的：没作废、没完成、没结案的生产单（草稿也算，排了就占产能），按交期、款式品类合计
     * 还要做的件数（数量 − 良品，良品 = 已回 − 次品）。exclude 是改草稿时的这张单自己
     */
    @Select("<script>SELECT p.factory_id, p.due_date, s.category_id, "
            + "SUM(GREATEST(pi.qty - (pi.returned_qty - pi.defect_qty), 0)) AS qty "
            + "FROM prd_order p JOIN prd_order_item pi ON pi.prd_order_id = p.id JOIN sty_style s ON s.id = p.style_id "
            + "WHERE p.status IN ('DRAFT', 'PENDING', 'APPROVED', 'RUNNING') AND p.factory_id IN "
            + "<foreach collection='factoryIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "<if test='exclude != null and !exclude.isEmpty()'> AND p.id NOT IN "
            + "<foreach collection='exclude' item='id' open='(' separator=',' close=')'>#{id}</foreach></if> "
            + "GROUP BY p.factory_id, p.due_date, s.category_id</script>")
    List<LoadRow> selectLoads(@Param("factoryIds") Collection<Long> factoryIds, @Param("exclude") Collection<Long> exclude);

    record LoadRow(long factoryId, LocalDate dueDate, Long categoryId, long qty) {
    }

    /** 款式的品类 */
    @Select("<script>SELECT id, category_id FROM sty_style WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<StyleCategoryRow> selectStyleCategories(@Param("ids") Collection<Long> styleIds);

    record StyleCategoryRow(long id, Long categoryId) {
    }
}
