package com.lingmao.scm.module.dev.service;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import java.util.List;
public interface SampleHomeService {
    enum Kind { REVIEW,LATE }
    record Entry(DocRef ref,String title,String sub,AttachmentVO image){}
    record Group(long total,List<Entry> items){}
    long makingCount(long companyId);
    Group todos(long companyId,Kind kind,Long ownerId);
}
