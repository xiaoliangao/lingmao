package com.lingmao.scm.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.page.StatusCount;
import com.lingmao.scm.module.biz.dto.ApprovalScope;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.dto.OrderRowVO;
import com.lingmao.scm.module.order.dto.OrderTotals;
import com.lingmao.scm.module.order.dto.StyleOrderRowVO;
import com.lingmao.scm.module.order.entity.Order;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 列表（XML）：已排产、已回货由明细加总；codes 是关键字里的几个单号、款号（Keywords） */
    IPage<OrderRowVO> selectRowPage(IPage<OrderRowVO> page, @Param("q") OrderQuery query, @Param("codes") List<String> codes,
                                    @Param("scope") DataScope scope);

    /** 首页在手订单张数、件数：与列表使用同一权限和过滤条件，独立聚合。 */
    OrderTotals selectTotals(@Param("q") OrderQuery query, @Param("codes") List<String> codes, @Param("scope") DataScope scope);

    /** 各状态几张（XML），条件和列表一样，不看 q.status */
    List<StatusCount> selectCounts(@Param("q") OrderQuery query, @Param("codes") List<String> codes, @Param("scope") DataScope scope);

    /** 款式详情的"需求订单"页签（XML）：这个款的件数、锁定的版本号 */
    IPage<StyleOrderRowVO> selectStylePage(IPage<StyleOrderRowVO> page, @Param("styleId") long styleId, @Param("scope") DataScope scope);

    /** 齐料批量访问：订单、生产列表、在制看板分别授权，不能只凭存在生产单放行。 */
    List<Order> selectKittingVisible(@Param("ids") Collection<Long> ids, @Param("scope") DataScope scope,
                                    @Param("canOrder") boolean canOrder, @Param("canProduction") boolean canProduction,
                                    @Param("canBoard") boolean canBoard,
                                    @Param("orderApproval") ApprovalScope orderApproval,
                                    @Param("productionApproval") ApprovalScope productionApproval,
                                    @Param("companyId") Long companyId);

    /** 改、删、动作之前锁住单头：和审核、变更写回排队 */
    @Select("SELECT * FROM ord_order WHERE id = #{id} FOR UPDATE")
    Order lockById(@Param("id") long id);
}
