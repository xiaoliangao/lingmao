package com.lingmao.scm.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.entity.RoleDataScope;

import java.util.Objects;

/**
 * 单据的数据范围（PRD §3.2、架构 §8、RFC 001 §8）：数据范围是"全部"的人看所有人的单，"本人"的只看自己相关的——
 * 制单人是自己，或者单据上的业务员、跟单员、采购员……是自己（哪几列由各单据定）。列表、详情、导出都要过它，详情不在范围里返回 404。
 * <p>
 * 显式地加条件，不用 MyBatis-Plus 的数据权限插件：插件在 SQL 执行前偷偷改 SQL，复杂查询上容易加错，读代码也看不出来。
 * <pre>
 * DataScope scope = DataScope.current(query.isMine());         // "我的 / 全部"切换：mine = true 时谁都只看自己的
 * scope.apply(wrapper, Order::getCreateBy, Order::getSalesId, Order::getMerchId);
 * // 本人时相当于 AND (create_by = 我 OR sales_id = 我 OR merch_id = 我)；全部时什么都不加
 *
 * DataScope.current(false).check(o.getCreateBy(), o.getSalesId(), o.getMerchId());   // 详情：不在范围里 → 404
 * </pre>
 * Mapper XML 里手写的查询：把它当参数传进去（@Param("scope") DataScope scope），用 &lt;if&gt; 加条件，值走 #{}，不拼字符串：
 * <pre>
 * &lt;if test="scope.self"&gt;
 *   AND (o.create_by = #{scope.userId} OR o.sales_id = #{scope.userId} OR o.merch_id = #{scope.userId})
 * &lt;/if&gt;
 * </pre>
 * 列名各单据不一样，所以条件写在各自的 XML 里，这里只给"要不要加"（isSelf）和"我是谁"（getUserId）。
 * <p>
 * "全部"看权限里有没有 data:all：加载权限时有一个启用的角色是"全部"（或者是超级管理员）就多给它（RFC 001 §18），不另外查库。
 * 没登录（userId 为空）算"本人"，条件里 列 = NULL 永远不成立，什么都看不到。
 * <p>
 * 品类线（RFC 013 §2）是另一道，也装在这里（{@link #getLines()}）：单据的 Mapper XML 已经收了 scope，
 * 按线筛写 &lt;if test="scope.lines.limited"&gt;，不用每个查询再加一个参数；详情另调 LineScope.check。
 * <p>
 * 为什么放 security 不放 common：它要读当前登录的人和 data:all，common 不能依赖 security、module（ArchitectureTest 规则 5）。
 * 为什么是静态的 current() 不是注入的 bean：和 LoginUser.currentUserId() 一样只读当前请求的登录信息，没有别的依赖；
 * 单测里照样能控制——往 SecurityContextHolder 里放一个登录用户就行（DataScopeTest）
 */
public final class DataScope {

    /** 当前用户；没登录是 null */
    private final Long userId;

    /** true = 看全部，不加条件 */
    private final boolean all;

    /** 品类线：和全部 / 本人叠在一起 */
    private final LineScope lines;

    private DataScope(Long userId, boolean all, LineScope lines) {
        this.userId = userId;
        this.all = all;
        this.lines = lines;
    }

    /**
     * 当前登录用户这次查询的范围。mine 是列表上的"我的 / 全部"切换：有"全部"范围的人选了"我的"，也按本人查；
     * 没有"全部"范围的人传什么都是本人
     */
    public static DataScope current(boolean mine) {
        return new DataScope(LoginUser.currentUserId(), !mine && LoginUser.hasAuthority(RoleDataScope.ALL_AUTHORITY), LineScope.current());
    }

    /**
     * 只留品类线、不按本人筛：自己看得见的单据要用到别人单据上的汇总时用（首页跟单看自己的生产单缺的料晚不晚于开裁，
     * 那些采购行的采购员是别人）。只给内部汇总，查出来的别人的单据不能原样交给页面
     */
    public DataScope linesOnly() {
        return new DataScope(userId, true, lines);
    }

    /**
     * 审核不受数据范围限制（PRD §3.2，用户 11-21 定）：角色勾了这个（权限里有 data:audit-any），又有这种单的审核权限，
     * 待审的单和自己审过的单不按本人筛（品类线照旧）。只给单级审核的变更单、物料出入库单、盘点单用；
     * 多级审批的单另有例外（轮到我审的能看，ApprovalService）。
     * <pre>
     * DataScope.current(false).orAuditing(c.getStatus() == PENDING, c.getAuditBy(), "order:change:audit").check(...)   // 详情、审核
     * DataScope.current(mine &amp;&amp; !auditForMe).orAuditing(auditForMe, null, "order:change:audit")                        // 待我审核列表（SQL 只列待审的）
     * </pre>
     * 改单、删单不用它：别人的草稿照旧改不了
     *
     * @param pending        这张单现在等着审（列表上是"待我审核"勾上了）
     * @param auditBy        单据的审核人：是我就算（审完回详情还看得到，能反审核）；列表传 null
     * @param auditAuthority 这种单的审核权限
     */
    public DataScope orAuditing(boolean pending, Long auditBy, String auditAuthority) {
        boolean auditable = pending || (userId != null && userId.equals(auditBy));
        if (all || !auditable
                || !LoginUser.hasAuthority(RoleDataScope.AUDIT_ANY_AUTHORITY) || !LoginUser.hasAuthority(auditAuthority)) {
            return this;
        }
        return new DataScope(userId, true, lines);
    }

    /** 看全部：不加任何条件 */
    public boolean isAll() {
        return all;
    }

    /** 只看本人的：XML 里 &lt;if test="scope.self"&gt; 用它 */
    public boolean isSelf() {
        return !all;
    }

    /** 当前用户 id，XML 里 #{scope.userId}；没登录是 null */
    public Long getUserId() {
        return userId;
    }

    /** 当前用户能看的品类线，XML 里 scope.lines.limited、scope.lines.values */
    public LineScope getLines() {
        return lines;
    }

    /**
     * 给 LambdaQueryWrapper 加条件：本人时 AND (列1 = 我 OR 列2 = 我 …)，全部时不动。返回同一个 wrapper，可以接着链式写。
     * 列至少一个，一般是制单人 create_by 加上单据的负责人列
     */
    @SafeVarargs
    public final <T> LambdaQueryWrapper<T> apply(LambdaQueryWrapper<T> wrapper, SFunction<T, ?>... ownerColumns) {
        if (ownerColumns.length == 0) {
            throw new IllegalArgumentException("数据范围至少要按一列筛（一般是 create_by）");
        }
        if (all) {
            return wrapper;
        }
        if (userId == null) {
            return wrapper.apply("1 = 0");
        }
        return wrapper.and(w -> {
            for (int i = 0; i < ownerColumns.length; i++) {
                if (i > 0) {
                    w.or();
                }
                w.eq(ownerColumns[i], userId);
            }
        });
    }

    /** 这张单在不在范围里：全部时总在；本人时这几个人里有一个是我就在（传单据上的制单人、负责人，空的跳过） */
    public boolean canSee(Long... ownerIds) {
        if (all) {
            return true;
        }
        for (Long ownerId : ownerIds) {
            if (userId != null && Objects.equals(ownerId, userId)) {
                return true;
            }
        }
        return false;
    }

    /** 详情、动作前查：不在范围里抛 404，和"不存在"一样不区分（docs/api/README §3.1），不让人试出别人的单号 */
    public void check(Long... ownerIds) {
        if (!canSee(ownerIds)) {
            throw new BizException(404, "单据不存在");
        }
    }
}
