package com.lingmao.scm.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.order.entity.OrderItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /** 一张订单的明细，按行号；件数 0 的（变更单减掉的）也在 */
    @Select("SELECT * FROM ord_order_item WHERE order_id = #{orderId} ORDER BY seq, id")
    List<OrderItem> selectByOrder(@Param("orderId") long orderId);

    @Select("<script>SELECT * FROM ord_order_item WHERE order_id IN "
            + "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY order_id, seq, id</script>")
    List<OrderItem> selectByOrders(@Param("orderIds") Collection<Long> orderIds);

    @Delete("DELETE FROM ord_order_item WHERE order_id = #{orderId}")
    int deleteByOrder(@Param("orderId") long orderId);

    /** 列表"款式"一列：每张订单有哪些款，按第一次出现的行号 */
    record OrderStyle(Long orderId, Long styleId) {
    }

    @Select("<script>SELECT order_id, style_id FROM ord_order_item WHERE qty &gt; 0 AND order_id IN "
            + "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY order_id, style_id ORDER BY order_id, min(seq)</script>")
    List<OrderStyle> selectStyles(@Param("orderIds") Collection<Long> orderIds);

    /** 这几张订单里还有 SKU 没排满的（已排产 < 件数）：列表、详情的"排单"按钮 */
    @Select("<script>SELECT DISTINCT order_id FROM ord_order_item WHERE scheduled_qty &lt; qty AND order_id IN "
            + "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    Set<Long> selectUnscheduledOrders(@Param("orderIds") Collection<Long> orderIds);

    /** 已排产加减（生产排单、删、作废草稿）；减成负数被表上的 CHECK 拦住 */
    @Update("UPDATE ord_order_item SET scheduled_qty = scheduled_qty + #{delta} WHERE id = #{id}")
    int addScheduled(@Param("id") long id, @Param("delta") int delta);

    /** 已回货（只算良品 = 回货 − 次品，甲方 11-19）加减：登记回货、作废回货单 */
    @Update("UPDATE ord_order_item SET returned_qty = returned_qty + #{delta} WHERE id = #{id}")
    int addReturned(@Param("id") long id, @Param("delta") int delta);
}
