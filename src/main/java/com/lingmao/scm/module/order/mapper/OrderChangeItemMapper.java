package com.lingmao.scm.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.order.entity.OrderChangeItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Mapper
public interface OrderChangeItemMapper extends BaseMapper<OrderChangeItem> {

    @Select("SELECT * FROM ord_change_item WHERE change_id = #{changeId} ORDER BY seq, id")
    List<OrderChangeItem> selectByChange(@Param("changeId") long changeId);

    @Select("<script>SELECT * FROM ord_change_item WHERE change_id IN "
            + "<foreach collection='changeIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY change_id, seq, id</script>")
    List<OrderChangeItem> selectByChanges(@Param("changeIds") Collection<Long> changeIds);

    @Delete("DELETE FROM ord_change_item WHERE change_id = #{changeId}")
    int deleteByChange(@Param("changeId") long changeId);

    /** 这几行订单明细里被变更明细指着的（不能删，件数记 0） */
    @Select("<script>SELECT DISTINCT order_item_id FROM ord_change_item WHERE order_item_id IN "
            + "<foreach collection='itemIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    Set<Long> selectReferencedItems(@Param("itemIds") Collection<Long> itemIds);
}
