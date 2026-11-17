package com.lingmao.scm.module.inventory.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.inventory.dto.InvDocCreateReq;
import com.lingmao.scm.module.inventory.dto.InvDocCreatedVO;
import com.lingmao.scm.module.inventory.dto.InvDocDetailVO;
import com.lingmao.scm.module.inventory.dto.InvDocDraftVO;
import com.lingmao.scm.module.inventory.dto.InvDocFromSourceReq;
import com.lingmao.scm.module.inventory.dto.InvDocQuery;
import com.lingmao.scm.module.inventory.dto.InvDocRowVO;
import com.lingmao.scm.module.inventory.dto.InvDocSaveReq;
import com.lingmao.scm.module.inventory.entity.InvDocType;

import java.util.List;

/** 物料入库单、物料出库单（docs/api/04 §3.2、§3.3）：审核即过账，过过账又反审核回来的草稿只能作废 */
public interface InvDocService {

    PageResult<InvDocRowVO> page(InvDocQuery query);

    InvDocDetailVO detail(long id);

    /** 手工新建草稿：只收不挂源单的类型 */
    InvDocCreatedVO create(InvDocCreateReq req);

    /** 改草稿，整体覆盖；返回新的 version */
    int update(long id, InvDocSaveReq req);

    /** 删草稿：没过过账的才能删（过过账的只能作废） */
    void delete(long id);

    /** 状态动作：audit 审核过账、unaudit 反审核、void 作废 */
    void act(long id, String action, DocActionReq req);

    /** 由源单带出（不存库）；source 是 PURCHASE_ORDER / PRODUCTION_ORDER */
    InvDocDraftVO prefill(BizType source, long sourceId, InvDocType docType, List<Long> itemIds, Long fromWarehouseId);

    /** 由源单生成；req.post 时马上审核过账（调用方先查好审核权限） */
    InvDocCreatedVO fromSource(BizType source, long sourceId, InvDocFromSourceReq req);

    byte[] export(InvDocQuery query);

    /** 详情的公共页签（logs、attachments……）先过它：不存在、不在数据范围里 404 */
    void checkVisible(long id);
}
