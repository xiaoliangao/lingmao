package com.lingmao.scm.module.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.purchase.entity.MrpCalcTransit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface MrpCalcTransitMapper extends BaseMapper<MrpCalcTransit> {

    @Select("SELECT t.* FROM mrp_calc_transit t JOIN mrp_calc_item i ON i.id = t.calc_item_id WHERE i.calc_id = #{calcId} ORDER BY t.id")
    List<MrpCalcTransit> selectByCalc(@Param("calcId") long calcId);

    @Delete("DELETE FROM mrp_calc_transit WHERE calc_item_id = #{itemId}")
    void deleteByItem(@Param("itemId") long itemId);

    @Delete("DELETE FROM mrp_calc_transit t USING mrp_calc_item i WHERE i.id = t.calc_item_id AND i.calc_id = #{calcId}")
    void deleteByCalc(@Param("calcId") long calcId);

    /**
     * 采购单草稿删行、删单前：没确认的（草稿、已关闭的）算料单记着用了这些采购行的，一起删掉（不然外键拦住）。
     * 采购单是从已审核反审核回来的；被已确认的算料单用着的反审核不了（1005），所以这里不会碰到已确认的。
     * 草稿算料单少了这几条，确认时按最新的数再算一遍会拦住（"可用量已变化"）；已关闭的只是少了一条历史
     */
    @Delete("<script>DELETE FROM mrp_calc_transit t USING mrp_calc_item i, mrp_calc c "
            + "WHERE i.id = t.calc_item_id AND c.id = i.calc_id AND c.status &lt;&gt; 'CONFIRMED' AND t.purchase_item_id IN "
            + "<foreach collection='lineIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    void deleteUsesOfLines(@Param("lineIds") Collection<Long> lineIds);

    /** 把这几条采购行用作本次使用在途的已确认算料单（采购单反审核要拦、下游单据要列） */
    record LineUser(Long lineId, Long calcId, String calcNo, String status, Long createBy, String orderNos, String styleCodes) {
    }

    @Select("<script>SELECT t.purchase_item_id AS line_id, c.id AS calc_id, c.no AS calc_no, c.status, c.create_by, "
            + "i.order_nos, i.style_codes FROM mrp_calc_transit t JOIN mrp_calc_item i ON i.id = t.calc_item_id "
            + "JOIN mrp_calc c ON c.id = i.calc_id WHERE c.status = 'CONFIRMED' AND t.purchase_item_id IN "
            + "<foreach collection='lineIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY t.id</script>")
    List<LineUser> selectLineUsers(@Param("lineIds") Collection<Long> lineIds);
}
