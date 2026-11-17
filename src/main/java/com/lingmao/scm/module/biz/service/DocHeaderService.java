package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.common.entity.DocEntity;
import com.lingmao.scm.module.biz.dto.DocHeaderVO;
import com.lingmao.scm.module.biz.entity.BizType;

/**
 * 详情的公共部分（DocHeaderVO，docs/api/README §4.3）由这里统一填，各单据只填自己的字段：
 * <pre>
 * PurchaseOrderDetailVO vo = docHeaderService.fill(new PurchaseOrderDetailVO(), BizType.PURCHASE_ORDER, po, po.getStatus());
 * vo.setCompanyName(…);           // 主体在 basic，biz 用不了 basic，由单据的域填
 * vo.setSupplierName(…); vo.setItems(…);
 * vo.setActions(DocActions.of(FLOW, po.getStatus(), "purchase:order:").approval(vo.getApproval()).list());
 * </pre>
 */
public interface DocHeaderService {

    /**
     * 填 id、单号、状态（枚举名）、版本、主体 id、备注、制单人和审核人（带昵称）、创建和修改时间、
     * 审批块（多级审批的三种单据才有，别的 null）、没处理的提醒数。actions 和 companyName 不填。返回传进来的 vo
     */
    <V extends DocHeaderVO> V fill(V vo, BizType type, DocEntity doc, Enum<?> status);
}
