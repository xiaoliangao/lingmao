package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.DocNoticeVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.biz.service.DocGuard;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DocCommonServiceImpl implements DocCommonService {

    private final DocLogService docLogService;
    private final AttachmentService attachmentService;
    private final ReferenceService referenceService;
    private final DocNoticeService docNoticeService;

    @Override
    public List<DocLogVO> logs(BizType type, long id, DocGuard guard) {
        guard.check(id);
        return docLogService.list(type, id);
    }

    @Override
    public List<AttachmentVO> attachments(BizType type, long id, DocGuard guard) {
        guard.check(id);
        return attachmentService.list(type, id, Attachment.FILE);
    }

    @Override
    @Transactional
    public List<AttachmentVO> replaceAttachments(BizType type, long id, List<AttachmentReq> files, DocGuard guard) {
        guard.check(id);
        return attachmentService.replace(type, id, Attachment.FILE, files);
    }

    @Override
    public List<DocRef> references(BizType type, long id, DocGuard guard) {
        guard.check(id);
        return referenceService.referrers(type, id);
    }

    @Override
    public List<DocNoticeVO> notices(BizType type, long id, DocGuard guard) {
        guard.check(id);
        return docNoticeService.list(type, id);
    }

    @Override
    @Transactional
    public void handleNotice(BizType type, long id, long noticeId, String remark, DocGuard guard) {
        guard.check(id);
        docNoticeService.handle(type, id, noticeId, remark);
    }
}
