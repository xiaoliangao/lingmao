package com.lingmao.scm.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.page.StatusCount;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.dto.OrderRowVO;
import com.lingmao.scm.module.order.dto.OrderTotals;
import com.lingmao.scm.module.order.dto.StyleOrderRowVO;
import com.lingmao.scm.module.order.entity.Order;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 列表（XML）：已排产、已回货由明细加总；codes 是关键字里的几个单号、款号（Keywords） */
    IPage<OrderRowVO> selectRowPage(IPage<OrderRowVO> page, @Param("q") OrderQuery query, @Param("codes") List<String> codes,
                                    @Param("scope") DataScope scope);

    /** 张数、件数合计（XML），条件和列表一样 */
    OrderTotals selectTotals(@Param("q") OrderQuery query, @Param("codes") List<String> codes, @Param("scope") DataScope scope);

    /** 各状态几张（XML），条件和列表一样，不看 q.status */
    List<StatusCount> selectCounts(@Param("q") OrderQuery query, @Param("codes") List<String> codes, @Param("scope") DataScope scope);

    /** 款式详情的"需求订单"页签（XML）：这个款的件数、锁定的版本号 */
    IPage<StyleOrderRowVO> selectStylePage(IPage<StyleOrderRowVO> page, @Param("styleId") long styleId, @Param("scope") DataScope scope);

    /** 改、删、动作之前锁住单头：和审核、变更写回排队 */
    @Select("SELECT * FROM ord_order WHERE id = #{id} FOR UPDATE")
    Order lockById(@Param("id") long id);

    /** 成品发货：共享锁，几张发货单可以同时拿，取消订单（FOR UPDATE）要等它们提交 */
    @Select("SELECT * FROM ord_order WHERE id = #{id} FOR SHARE")
    Order lockShareById(@Param("id") long id);
}
