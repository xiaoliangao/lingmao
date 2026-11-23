package com.lingmao.scm.module.dev.service.impl;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import com.lingmao.scm.module.dev.repository.SampleRepository;
import com.lingmao.scm.security.DataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class SampleTagObjectProvider implements TagObjectProvider {
    private final SampleRepository repository;
    @Override public TagObjectType type(){return TagObjectType.SAMPLE;}
    @Override public Target resolve(long id,Access access){
        TagObjectProvider.permission(access,"dev:sample:list","dev:sample:edit");
        Long company=CompanyContext.header(true);if(company==null)throw new BizException(400,"请选择样衣主体公司");
        var sample=access.locks()?repository.lock(company,id,DataScope.current(false),null,null).sample():repository.read(company,id,DataScope.current(false)).sample();
        if(access.locks())SampleSupport.editable(sample);
        return TagObjectProvider.target(type(),id,sample.getCompanyId());
    }
}
