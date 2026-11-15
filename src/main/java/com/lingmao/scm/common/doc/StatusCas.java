package com.lingmao.scm.common.doc;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;

/**
 * 改状态一律"比较并交换"（架构 §4.2）：条件里带上查出来时的状态，还是它才改；改了 0 行，说明这期间被别人改了（或者删了）→ 1002。
 * 两个人同时点审核只有一个成功，双击也只生效一次（StatusCasIT 在真库上钉住了）。
 * <pre>
 * Supplier s = getEntity(id);
 * SupplierStatus to = FLOW.next("audit", s.getStatus());                       // 先查转换表（StatusFlow）
 * StatusCas.update(supplierMapper, new Supplier(),
 *         StatusCas.of(Supplier::getId, id, Supplier::getStatus, s.getStatus(), to)
 *                 .set(Supplier::getAdmitBy, userId)                          // 同时要改的别的列接着 .set
 *                 .set(Supplier::getAdmitTime, now));
 * docLogService.add(BizType.SUPPLIER, id, "audit", s.getStatus(), to, null);   // 操作记录，同一个事务
 * </pre>
 * 单据单头还要让版本号加一（架构 §4.3），接着写 {@code .setSql("version = version + 1")}；
 * 要同时核对页面带回来的版本号，再加 {@code .eq(Order::getVersion, version)}，对不上也是 0 行、1002。
 * <p>
 * 第二个参数传 new Supplier()，不传 null：MyBatis-Plus 的自动填充只对传进去的实体生效，传 null 时 update_time、update_by 不更新
 * （AuditMetaObjectHandler）。空实体的字段全是 null，不会进 SET；状态列标了 NEVER（RFC 001 §4.4），也只有这里的 .set 能改它。
 * 不要传查出来的那个实体：它的字段都有值，会被整条写回去，把别人刚保存的改动盖掉。
 * <p>
 * 为什么拆成 of 和 update 两步，不做成一个方法：一起改的列各单据不一样（审核人、准入时间、版本号），
 * 接着 .set 写比塞一个回调参数好读；update 把"0 行 → 1002"收在一处，用了它就不会忘了查行数。
 */
public final class StatusCas {

    private StatusCas() {
    }

    /**
     * SET 状态 = to WHERE id = ? AND 状态 = from。id 必传：漏了它，所有处在 from 状态的行都会被改掉。
     * 返回的条件可以接着 .set、.setSql、.eq
     */
    public static <T, S extends Enum<S>> LambdaUpdateWrapper<T> of(SFunction<T, ?> idColumn, Long id,
                                                                   SFunction<T, S> statusColumn, S from, S to) {
        if (id == null || to == null) {
            throw new IllegalArgumentException("比较并交换要有 id 和目标状态");
        }
        return new LambdaUpdateWrapper<T>()
                .set(statusColumn, to)
                .eq(idColumn, id)
                .eq(statusColumn, from);
    }

    /** 执行：mapper.update(空实体, 条件)。改了 0 行 → 1002，调用方的事务跟着回滚 */
    public static <T> void update(BaseMapper<T> mapper, T emptyEntity, Wrapper<T> cas) {
        if (emptyEntity == null) {
            throw new IllegalArgumentException("传 new Xxx()，不传 null：传 null 时修改人、修改时间不会自动填");
        }
        if (mapper.update(emptyEntity, cas) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, StatusFlow.CHANGED);
        }
    }
}
