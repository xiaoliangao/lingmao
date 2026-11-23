package com.lingmao.scm.module.dev.service.impl;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.dev.entity.Sample;
import com.lingmao.scm.module.dev.entity.SampleOrder;
import com.lingmao.scm.module.dev.repository.SampleRepository;
import com.lingmao.scm.security.DataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;
@Component @RequiredArgsConstructor
public class SampleReferrers implements ReferrerProvider {
    private final SampleRepository repository;
    @Override @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<DocRef> referrers(BizType type,long id){
        if(type!=BizType.STYLE)return List.of();Sample source=repository.convertedSource(id);if(source==null)return List.of();
        try{SampleSupport.permission("list");repository.read(source.getCompanyId(),source.getId(),DataScope.current(false));}
        catch(BizException hidden){if(hidden.getCode()!=403&&hidden.getCode()!=404)throw hidden;throw new BizException(1005,"存在不可见的有效来源，不能执行此操作");}
        return List.of(SampleReferences.of(source));
    }
    @Override @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<Long,DocRef> describe(BizType type,Collection<Long> ids){
        if(!Set.of(BizType.SAMPLE,BizType.SAMPLE_ORDER,BizType.SAMPLE_REVIEW).contains(type)||ids.isEmpty())return Map.of();
        SampleSupport.permission("list");Long company=CompanyContext.header(true);if(company==null)throw new BizException(400,"请选择样衣主体公司");
        Map<Long,DocRef> out=new LinkedHashMap<>();
        if(type==BizType.SAMPLE)repository.sampleIdentities(company,DataScope.current(false),ids).forEach(s->out.put(s.getId(),SampleReferences.of(s)));
        else if(type==BizType.SAMPLE_ORDER)repository.orderIdentities(company,DataScope.current(false),ids).forEach(o->out.put(o.getId(),SampleReferences.of(o)));
        else{
            var reviews=repository.reviewIdentities(company,DataScope.current(false),ids);
            Map<Long,SampleOrder> orders=repository.orderIdentities(company,DataScope.current(false),reviews.stream().map(v->v.getSampleOrderId()).toList())
                    .stream().collect(Collectors.toMap(SampleOrder::getId,o->o));
            reviews.forEach(v->{var order=orders.get(v.getSampleOrderId());out.put(v.getId(),DocRef.of(BizType.SAMPLE_REVIEW,v.getId(),order.getNo()+" 评审",v.getResult(),SampleReferences.REVIEWS,v.getCreateBy())
                    .withParent(order.getSampleId()).withCompany(company));});
        }
        return out;
    }
}
