package com.lingmao.scm.module.dev.service;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.dev.dto.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 样衣和当前打样单的公开业务入口；tenant/状态和审计字段不从客户端接收。 */
public interface SampleService {
    PageResult<SampleRowVO> page(SampleQuery query);
    Map<String,Long> counts(SampleQuery query);
    SampleDetailVO detail(long companyId,long id);
    SampleVersionPage versions(long companyId,long id,Integer beforeVersion,int size);
    SampleRef byStyle(long companyId,long styleId);
    SampleCreated create(SampleCreateReq request);
    SampleActionResult update(long companyId,long id,SampleUpdateReq request);
    SampleRemoved delete(long companyId,long id,int version);
    SampleImagesResult images(long companyId,long id,SampleImagesReq request);
    SampleActionResult stop(long companyId,long id,SampleReasonReq request);
    SampleActionResult reopen(long companyId,long id,SampleReasonReq request);
    SampleConvertPreview convertPreview(long companyId,long id,String styleClass);
    SampleConvertResult convert(long companyId,long id,SampleConvertReq request);
    PageResult<DocLogVO> logs(long companyId,long id,PageQuery query);
    List<DocRef> references(long companyId,long id);
    SampleActionResult saveOrder(long companyId,long orderId,SampleOrderReq request);
    SampleActionResult dispatch(long companyId,long orderId,SampleDispatchReq request);
    SampleDispatchPreview dispatchPreview(long companyId,long orderId,LocalDate needDate);
    SampleActionResult plans(long companyId,long orderId,SampleNodePlansReq request);
    SampleActionResult done(long companyId,long orderId,long nodeId,SampleNodeDoneReq request);
    SampleActionResult undoDone(long companyId,long orderId,long nodeId,int version,int sampleVersionLock);
    SampleActionResult review(long companyId,long orderId,SampleReviewReq request);
    List<AttachmentVO> orderFiles(long companyId,long orderId);
    SampleOrderFilesResult orderFiles(long companyId,long orderId,SampleOrderFilesReq request);
    /** 审计、附件等只鉴权：只查轻量真实身份，绝不依赖缓存或完整详情。 */
    void checkReadable(long companyId,long sampleId);
    void checkOrderReadable(long companyId,long orderId);
    void checkReviewReadable(long companyId,long reviewId);
    /** 幂等已完成收据重新校验当前动作权限和真实归属，包括已删除样衣的有限身份。 */
    void authorizeReplay(String permission,long companyId,long sampleId,boolean removed);
    /** 换主体收据仍属于原请求主体；重验当前编辑权限及结果主体内的真实可见归属。 */
    void authorizeUpdateReplay(long originalCompanyId,long resultingCompanyId,long sampleId);
    void authorizeOrderReplay(String permission,long companyId,long orderId);
}
