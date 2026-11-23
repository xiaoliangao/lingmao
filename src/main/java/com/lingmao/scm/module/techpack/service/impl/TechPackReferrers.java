package com.lingmao.scm.module.techpack.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.dto.StatusTone;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.module.techpack.service.SamplePackOwner;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.*;

/** 引用只查轻量头；样衣 owner 必须真实整批授权，不能展开快照再过滤。 */
@Component @RequiredArgsConstructor
class TechPackReferrers implements ReferrerProvider {
    static final StatusLabels<PackStatus> PACK_STATUS=StatusLabels.of(PackStatus.class)
        .with(PackStatus.DRAFT,"草稿",StatusTone.OFF).with(PackStatus.EFFECTIVE,"已生效",StatusTone.DONE).with(PackStatus.VOIDED,"已作废",StatusTone.VOID);
    private final TechPackMapper techPackMapper;
    private final SamplePackOwner sampleOwner;
    private final StyleService styles;
    private final DeploymentNamespace namespace;

    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<DocRef> referrers(BizType type,long id){
        if(type!=BizType.SAMPLE)return List.of();
        Long company=CompanyContext.header(true);if(company==null)throw new BizException(400,"请选择样衣主体公司");
        // 来自样衣详情的当前实际可见 owner；已转款包通过款式入口访问，不把历史所属当 owner。
        sampleOwner.checkReadable(company,id);
        long total=techPackMapper.countSamplePacks(namespace.tenant(),company,id);
        if(total==0)return List.of();
        if(!LoginUser.hasAuthority("techpack:pack:list"))throw new BizException(1005,"存在不可见的有效引用，不能执行此操作");
        if(total>100)throw new BizException(1005,"资料包引用超过100条，请在研发BOM版本页分页查看完整列表");
        var heads=techPackMapper.selectSamplePacks(namespace.tenant(),company,id,null,100);
        var owner=sampleOwner.references(company,List.of(id)).get(id);
        return heads.stream().map(p->DocRef.of(BizType.TECH_PACK,p.id(),owner.code()+" "+p.stage().versionName(p.version()),p.status(),PACK_STATUS,p.createBy())
                .withKind("SAMPLE").withParent(id).withCompany(company)).toList();
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<Long,DocRef> describe(BizType type,Collection<Long> requested){
        if(type!=BizType.TECH_PACK||requested.isEmpty())return Map.of();
        if(requested.size()>100||requested.stream().anyMatch(id->id==null||id<=0))throw new BizException(400,"一次最多查询100个资料包引用");
        List<Long> ids=requested.stream().distinct().toList();
        List<TechPack> rows=techPackMapper.selectList(new LambdaQueryWrapper<TechPack>()
                .select(TechPack::getId,TechPack::getStyleId,TechPack::getSampleId,TechPack::getStage,TechPack::getVersion,TechPack::getStatus,TechPack::getCreateBy)
                .in(TechPack::getId,ids));
        if(rows.size()!=ids.size())throw new BizException(404,"资料包不存在或无权访问");
        List<Long> sampleIds=rows.stream().filter(p->p.getStyleId()==null).map(TechPack::getSampleId).distinct().toList();
        Long company=null;Map<Long,SamplePackOwner.Reference> samples=Map.of();
        if(!sampleIds.isEmpty()){
            if(!LoginUser.hasAuthority("techpack:pack:list"))throw new BizException(403,"没有资料包查看权限");
            company=CompanyContext.header(true);if(company==null)throw new BizException(400,"请选择样衣主体公司");
            samples=sampleOwner.references(company,sampleIds);
        }
        var styleRefs=styles.refs(rows.stream().map(TechPack::getStyleId).filter(Objects::nonNull).distinct().toList());
        Map<Long,DocRef> out=new LinkedHashMap<>();
        for(TechPack p:rows){
            if(p.getStyleId()!=null){var style=styleRefs.get(p.getStyleId());String no=(style==null?"":style.code()+" ")+p.getStage().versionName(p.getVersion());
                out.put(p.getId(),DocRef.of(type,p.getId(),no,p.getStatus(),PACK_STATUS,p.getCreateBy()).withParent(p.getStyleId()));}
            else{var sample=samples.get(p.getSampleId());if(sample==null)throw new BizException(404,"资料包不存在或无权访问");
                out.put(p.getId(),DocRef.of(type,p.getId(),sample.code()+" "+p.getStage().versionName(p.getVersion()),p.getStatus(),PACK_STATUS,p.getCreateBy())
                        .withKind("SAMPLE").withParent(p.getSampleId()).withCompany(company));}
        }
        return out;
    }
}
