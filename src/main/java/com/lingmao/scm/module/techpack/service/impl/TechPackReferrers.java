package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.dto.StatusTone;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.techpack.dto.PackOwner;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资料包在引用查询里的样子（ReferrerProvider）：订单锁的生产 BOM 换了版本时，变更提醒的来源、首页"上游已变更"要显示它。
 * 单号写"款号 V2"，没有自己的详情页，挂在款式详情里（parentId = 款式 id）。资料包不引用别的单据，referrers 一律空
 */
@Component
@RequiredArgsConstructor
class TechPackReferrers implements ReferrerProvider {

    /** 和前端 types/techpack.ts 的 PACK_STATUS 一样 */
    static final StatusLabels<PackStatus> PACK_STATUS = StatusLabels.of(PackStatus.class)
            .with(PackStatus.DRAFT, "草稿", StatusTone.OFF)
            .with(PackStatus.EFFECTIVE, "已生效", StatusTone.DONE)
            .with(PackStatus.VOIDED, "已作废", StatusTone.VOID);

    private final TechPackMapper techPackMapper;
    private final PackOwners packOwners;

    @Override
    public List<DocRef> referrers(BizType type, long id) {
        return List.of();
    }

    @Override
    public Map<Long, DocRef> describe(BizType type, Collection<Long> ids) {
        if (type != BizType.TECH_PACK || ids.isEmpty()) {
            return Map.of();
        }
        List<TechPack> packs = techPackMapper.selectByIds(ids);
        Map<Long, PackOwner> owners = packOwners.styles(packs.stream().map(TechPack::getStyleId).toList());
        Map<Long, DocRef> refs = new HashMap<>();
        for (TechPack p : packs) {
            PackOwner owner = TechPackServiceImpl.at(owners, p.getStyleId());
            String no = (owner == null ? "" : owner.code() + " ") + p.getStage().versionName(p.getVersion());
            refs.put(p.getId(), DocRef.of(BizType.TECH_PACK, p.getId(), no, p.getStatus(), PACK_STATUS, p.getCreateBy())
                    .withParent(p.getStyleId()));
        }
        return refs;
    }
}
