package com.lingmao.scm.module.dev.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.dev.entity.Sample;
import com.lingmao.scm.module.dev.entity.SampleStatus;
import com.lingmao.scm.module.dev.repository.SampleRepository;
import com.lingmao.scm.module.techpack.dto.PackOwner;
import com.lingmao.scm.module.techpack.service.SamplePackOwner;
import com.lingmao.scm.security.DataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SamplePackOwnerImpl implements SamplePackOwner {
    private final SampleRepository repository;
    private final SampleSupport support;
    private final AttachmentService attachments;

    @Override public java.util.Map<Long,Reference> references(long companyId,java.util.Collection<Long> sampleIds){
        SampleSupport.permission("list");
        var rows=repository.sampleIdentities(companyId,DataScope.current(false),sampleIds);SampleSupport.company(companyId);
        return rows.stream().collect(java.util.stream.Collectors.toMap(Sample::getId,s->new Reference(s.getId(),s.getCode(),s.getDesignerId())));
    }
    @Override public Owned resolve(long companyId,long sampleId,Access access){
        SampleSupport.permission(access==Access.CONVERT?"convert":"list");
        // 先按轻量身份中的真实主体与 scope 鉴权，再比对请求头；隐藏对象不泄露归属公司。
        Sample sample=access==Access.READ?repository.read(companyId,sampleId,DataScope.current(false)).sample():
                repository.lock(companyId,sampleId,DataScope.current(false),null,null).sample();
        SampleSupport.company(companyId);
        if(access==Access.EDIT){
            // 旧 owner 身份在等父锁时可能已完成转款，必须明确要求客户端刷新所属与版本。
            if(sample.getStatus()==SampleStatus.CONVERTED)throw new BizException(1002,"资料包归属已经变化，请重新打开");
            SampleSupport.editable(sample);
        }
        if(access==Access.CONVERT&&sample.getStatus()!=SampleStatus.PASSED)throw new BizException(1002,"样衣尚未通过或已经转款");
        String locked=sample.getStatus()==SampleStatus.ELIMINATED||sample.getStatus()==SampleStatus.CONVERTED?"样衣已终止或转款，不能修改研发资料包":null;
        var owner=new PackOwner(sample.getCode(),sample.getName(),null,sample.getYear(),sample.getSeason(),sample.getCode(),
                sample.getSizeGroupId(),support.sampleColors(sample),sample.getEstTagPrice(),null,null,null,locked);
        return new Owned(companyId,sampleId,sample.getVersion(),owner);
    }
    @Override public void checkReadable(long companyId,long sampleId){
        SampleSupport.permission("list");repository.read(companyId,sampleId,DataScope.current(false));SampleSupport.company(companyId);
    }
    @Override @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public AttachmentVO mainImage(long companyId,long sampleId){
        checkReadable(companyId,sampleId);return attachments.firsts(BizType.SAMPLE,List.of(sampleId),Attachment.IMAGE).get(sampleId);
    }
}
