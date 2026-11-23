package com.lingmao.scm.module.techpack.service.impl;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagObjectProvider;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class TechPackTagProvider implements TagObjectProvider {
    private final PackOwners owners;
    private final StyleService styles;
    @Override public TagObjectType type(){return TagObjectType.TECH_PACK;}
    @Override public Target resolve(long id,Access access){
        TagObjectProvider.permission(access,"techpack:pack:list","techpack:pack:edit");
        if(access.locks()){
            var row=owners.lock(id,false);TagObjectProvider.editable(row.getStatus()==PackStatus.DRAFT);
            if(row.getStyleId()!=null)styles.checkUsable(row.getStyleId());
        }
        var identity=owners.readIdentity(id);
        return TagObjectProvider.target(type(),id,identity.companyId());
    }
}
