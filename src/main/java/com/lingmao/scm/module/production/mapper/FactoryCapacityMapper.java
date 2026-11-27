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
