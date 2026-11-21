package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.common.doc.FieldChange;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.entity.BizType;

import java.util.List;

/**
 * 操作记录（架构 §4.4、数据库设计 §4.1）：单据、主数据的每个动作写一行，详情页"操作记录"页签读它。
 * 写在做动作的那个事务里（各模块的 Service 方法本来就是 @Transactional）：动作回滚，记录也没了。
 * <p>
 * 不单独开接口：各资源的 controller 挂自己的 GET /api/&lt;资源&gt;/{id}/logs，调这里的 list 或 page，
 * 看记录的权限、数据范围就是看这条记录的（RFC 001 §14）。
 */
public interface DocLogService {

    /** 状态没变的动作：多级审批的中间级通过、改了关键字段（以前还有导出 PDF）。操作人取当前登录用户 */
    void add(BizType type, long docId, String action, String remark);

    /**
     * 状态动作：记下动作前后的状态（存枚举名）。新建时 from 传 null。
     * <pre>docLogService.add(BizType.SUPPLIER, id, "reject", SupplierStatus.ADMITTING, SupplierStatus.PENDING_ADMIT, req.getReason());</pre>
     */
    void add(BizType type, long docId, String action, Enum<?> from, Enum<?> to, String remark);

    /**
     * 档案的"修改"带上改了哪些字段（RFC 016 §3），存进 extra 的 diff；diff 是空的照样记一条（保存了、什么都没改）。
     * 字段表用 FieldDiff 列
     */
    void add(BizType type, long docId, String action, String remark, List<FieldChange> diff);

    /** 一条记录的全部操作记录，新的在前，带操作人昵称 */
    List<DocLogVO> list(BizType type, long docId);

    /** 同上，分页：记录多的（需求订单、生产单）用 */
    PageResult<DocLogVO> page(BizType type, long docId, PageQuery query);

    /** 删一条记录时，同一个事务里把它的操作记录一起删（RFC 003 §2）。删不掉自己时整个回滚，记录也还在 */
    void deleteByDoc(BizType type, long docId);
}
