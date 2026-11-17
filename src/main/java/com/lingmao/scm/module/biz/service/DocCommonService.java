package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.DocNoticeVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;

import java.util.List;

/**
 * 每种单据详情页都有的几个公共接口（docs/api/README §4.3）：操作记录、附件、下游单据、上游变更提醒。路径挂在各单据下面，
 * 权限码各单据不一样（看采购单的记录要 purchase:order:list），所以接口由各单据的 Controller 自己写，一个方法一行转到这里；
 * 每个方法都要一个 DocGuard，先查"这张单在不在、我能不能看"（数据范围），省得哪个接口忘了查、用 id 一个个试出别人的单。
 * <pre>
 * &#64;GetMapping("/{id}/logs")
 * &#64;PreAuthorize("hasAuthority('purchase:order:list')")
 * public R&lt;List&lt;DocLogVO&gt;&gt; logs(&#64;PathVariable("id") Long id) {
 *     return R.ok(docCommonService.logs(BizType.PURCHASE_ORDER, id, purchaseOrderService::checkVisible));
 * }
 * // 另外五个照样写：GET /{id}/attachments、PUT /{id}/attachments（:edit）、GET /{id}/references、
 * // GET /{id}/notices、POST /{id}/notices/{noticeId}/handle（处理提醒要 :edit；body 是 NoticeHandleReq）
 * </pre>
 * 为什么不做成一个公共的父类 Controller：权限码要写在注解里，父类里写不了各单据的；biz 的 Controller 别的域也不能依赖（ArchitectureTest）。
 * 附件是单据的随附文件，分类 FILE（款式图、物料图那种 IMAGE 由各自的接口管）
 */
public interface DocCommonService {

    /** 操作记录，新的在前 */
    List<DocLogVO> logs(BizType type, long id, DocGuard guard);

    /** 附件，按顺序 */
    List<AttachmentVO> attachments(BizType type, long id, DocGuard guard);

    /** 附件整组替换（AttachmentService.replace），返回替换后的 */
    List<AttachmentVO> replaceAttachments(BizType type, long id, List<AttachmentReq> files, DocGuard guard);

    /** "下游单据"页签：引用了这张单的单据，作废的不算 */
    List<DocRef> references(BizType type, long id, DocGuard guard);

    /** 上游变更提醒，没处理的在前 */
    List<DocNoticeVO> notices(BizType type, long id, DocGuard guard);

    /** 处理一条提醒；已经被别人处理了 → 1002 */
    void handleNotice(BizType type, long id, long noticeId, String remark, DocGuard guard);
}
