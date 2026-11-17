package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.entity.DocEntity;
import com.lingmao.scm.module.biz.dto.DocHeaderVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.module.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DocHeaderServiceImpl implements DocHeaderService {

    private final UserService userService;
    private final ApprovalService approvalService;
    private final DocNoticeService docNoticeService;

    @Override
    public <V extends DocHeaderVO> V fill(V vo, BizType type, DocEntity doc, Enum<?> status) {
        vo.setId(doc.getId());
        vo.setNo(doc.getNo());
        vo.setStatus(status.name());
        vo.setVersion(doc.getVersion());
        vo.setCompanyId(doc.getCompanyId());
        vo.setRemark(doc.getRemark());
        vo.setCreateBy(doc.getCreateBy());
        vo.setCreateTime(doc.getCreateTime());
        vo.setUpdateTime(doc.getUpdateTime());
        vo.setAuditBy(doc.getAuditBy());
        vo.setAuditTime(doc.getAuditTime());
        Set<Long> people = new HashSet<>();
        if (doc.getCreateBy() != null) {
            people.add(doc.getCreateBy());
        }
        if (doc.getAuditBy() != null) {
            people.add(doc.getAuditBy());
        }
        Map<Long, String> names = userService.nicknames(people);
        vo.setCreateByName(doc.getCreateBy() == null ? null : names.get(doc.getCreateBy()));
        vo.setAuditByName(doc.getAuditBy() == null ? null : names.get(doc.getAuditBy()));
        vo.setApproval(approvalService.supports(type) ? approvalService.view(type, doc.getId()) : null);
        vo.setPendingNotices(docNoticeService.countPending(type, doc.getId()));
        return vo;
    }
}
