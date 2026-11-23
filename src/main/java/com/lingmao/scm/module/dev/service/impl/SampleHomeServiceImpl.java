package com.lingmao.scm.module.dev.service.impl;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.dev.mapper.SampleSearch;
import com.lingmao.scm.module.dev.repository.SampleRepository;
import com.lingmao.scm.module.dev.service.SampleHomeService;
import com.lingmao.scm.security.DataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class SampleHomeServiceImpl implements SampleHomeService {
    private final SampleRepository repository;
    private final AttachmentService attachments;
    @Override @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public long makingCount(long companyId){
        SampleSupport.permission("list");SampleSupport.company(companyId);
        return repository.counts(companyId,DataScope.current(false),SampleSearch.all()).stream().filter(c->"MAKING".equals(c.status())).mapToLong(c->c.total()).sum();
    }
    @Override @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Group todos(long companyId,Kind kind,Long ownerId){
        SampleSupport.permission("list");if(kind==Kind.REVIEW)SampleSupport.permission("review");SampleSupport.company(companyId);
        var actual=SampleRepository.HomeKind.valueOf(kind.name());var scope=DataScope.current(false);
        long count=repository.homeCount(companyId,scope,actual,ownerId);
        var rows=repository.homePreview(companyId,scope,actual,ownerId,20);
        var images=attachments.firsts(BizType.SAMPLE,rows.stream().map(r->r.getId()).toList(),Attachment.IMAGE);
        return new Group(count,rows.stream().map(r->new Entry(SampleReferences.of(r),"样衣 "+r.getCode()+" "+r.getName(),
                "第 "+r.getCurrentSampleVersion()+" 版 · "+(kind==Kind.REVIEW?"待评审":"节点延期")+" · "+r.getHomeRankDate(),images.get(r.getId()))).toList());
    }
}
