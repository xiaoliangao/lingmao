package com.lingmao.scm.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.page.StatusCount;
import com.lingmao.scm.module.order.dto.OrderChangeQuery;
import com.lingmao.scm.module.order.dto.OrderChangeRowVO;
import com.lingmao.scm.module.order.entity.OrderChange;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Mapper
public interface OrderChangeMapper extends BaseMapper<OrderChange> {

    /** 列表（XML），制单时间倒序；数据范围跟着订单走（订单的制单人、业务员、跟单员），再加变更单的制单人 */
    IPage<OrderChangeRowVO> selectRowPage(IPage<OrderChangeRowVO> page, @Param("q") OrderChangeQuery query,
                                          @Param("codes") List<String> codes, @Param("scope") DataScope scope);

    /** 各状态几张（XML），不看 q.status、q.rejected；另外多一行 REJECTED：草稿里被驳回的 */
    List<StatusCount> selectCounts(@Param("q") OrderChangeQuery query, @Param("codes") List<String> codes,
                                   @Param("scope") DataScope scope);

    @Select("SELECT * FROM ord_change WHERE id = #{id} FOR UPDATE")
    OrderChange lockById(@Param("id") long id);

    /** 这张订单没审完的变更单（草稿或待审核），最多一张（uk_ord_change_open）；没有是 null */
    @Select("SELECT * FROM ord_change WHERE order_id = #{orderId} AND status IN ('DRAFT', 'PENDING')")
    OrderChange selectOpen(@Param("orderId") long orderId);

    /** 这张订单有没有变更单（删草稿订单前：有就只能作废） */
    @Select("SELECT EXISTS (SELECT 1 FROM ord_change WHERE order_id = #{orderId})")
    boolean orderHasChanges(@Param("orderId") long orderId);

    /** 这几张订单里有没审完的变更单的：列表、详情的"提变更""反审核"按钮 */
    @Select("<script>SELECT order_id FROM ord_change WHERE status IN ('DRAFT', 'PENDING') AND order_id IN "
            + "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    Set<Long> selectOpenOrders(@Param("orderIds") Collection<Long> orderIds);
}
