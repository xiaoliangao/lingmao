package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.service.ColorService;
import com.lingmao.scm.module.basic.service.SizeGroupService;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.style.dto.StylePackInfo;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.techpack.dto.PackOwner;
import com.lingmao.scm.module.techpack.dto.PackColorRef;
import com.lingmao.scm.module.techpack.dto.PackSizeRef;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.entity.PackOwnerSnapshot;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 资料包的主人（RFC 007 §6.2）：款式那边的东西集中在这里取，建版本、生效、修订、核价、PDF 都只用这里给的。
 * 二期研发 BOM 挂样衣时，style_id 为空的版本由 dev 实现的 SamplePackOwner 给同一个 PackOwner，只在这个类里加分支
 */
@Component
@RequiredArgsConstructor
class PackOwners {

    private final StyleService styleService;
    private final SizeGroupService sizeGroupService;
    private final ColorService colorService;
    private final AttachmentService attachmentService;
    private final TechPackMapper techPackMapper;
    private final com.lingmao.scm.module.techpack.service.SamplePackOwner sampleOwner;

    /** 主人 + 算出来的尺码、基码、颜色：详情、核价、生效检查都要 */
    record Ctx(PackOwner owner, List<String> sizes, String baseSize, List<ColorOption> colors) {

        List<Long> colorIds() {
            return owner.colorIds();
        }

        Long firstColorId() {
            return colors.isEmpty() ? null : colors.getFirst().id();
        }
    }

    /** 款式不存在 404 */
    PackOwner of(TechPack pack) {
        if(pack.getStyleId()!=null)return style(pack.getStyleId());
        var identity=readIdentity(pack.getId());
        sameOwner(identity,pack);
        return sampleOwner.resolve(identity.companyId(),identity.sampleId(),com.lingmao.scm.module.techpack.service.SamplePackOwner.Access.READ).owner();
    }

    TechPackMapper.OwnerIdentity readIdentity(long id) {
        var identity=techPackMapper.ownerIdentity(id);
        if(identity==null)throw missing();
        if(identity.styleId()==null){
            if(identity.sampleId()==null||identity.companyId()==null)throw missing();
            sampleOwner.checkReadable(identity.companyId(),identity.sampleId());
        }
        return identity;
    }

    /** 快照/正文的任何读取之前都先用小投影授权。 */
    TechPack read(long id){
        var identity=readIdentity(id);TechPack pack=techPackMapper.selectById(id);
        if(pack==null)throw missing();sameOwner(identity,pack);return pack;
    }

    /** 原 style 路径保持；样衣必须先父锁，再按需要的 TP id 升序加锁。 */
    TechPack lock(long id,boolean publication){
        var identity=techPackMapper.ownerIdentity(id);if(identity==null)throw missing();
        if(identity.styleId()==null){
            if(identity.sampleId()==null||identity.companyId()==null)throw missing();
            sampleOwner.resolve(identity.companyId(),identity.sampleId(),com.lingmao.scm.module.techpack.service.SamplePackOwner.Access.EDIT);
            if(publication){
                // 同一个样衣父锁串行化所有 DEV 版本动作；这里只锁目标与原生效版两行。
                List<TechPack> rows=techPackMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TechPack>()
                        .eq(TechPack::getSampleId,identity.sampleId()).isNull(TechPack::getStyleId)
                        .and(w->w.eq(TechPack::getId,id).or().eq(TechPack::getStatus,PackStatus.EFFECTIVE))
                        .select(TechPack::getId,TechPack::getStyleId,TechPack::getSampleId,TechPack::getVersionLock)
                        .orderByAsc(TechPack::getId).last("LIMIT 3 FOR UPDATE"));
                if(rows.size()>2)throw new com.lingmao.scm.common.exception.BizException(1002,"资料包状态已变化，请刷新后再试");
            }
        }
        TechPack pack=techPackMapper.lockById(id);if(pack==null)throw missing();sameOwner(identity,pack);
        if(!java.util.Objects.equals(identity.versionLock(),pack.getVersionLock()))
            throw new com.lingmao.scm.common.exception.BizException(1002,"资料包版本已变化，请刷新后重试");
        return pack;
    }
    private static void sameOwner(TechPackMapper.OwnerIdentity identity,TechPack pack){
        if(!java.util.Objects.equals(identity.styleId(),pack.getStyleId())||!java.util.Objects.equals(identity.sampleId(),pack.getSampleId()))
            throw new com.lingmao.scm.common.exception.BizException(1002,"资料包归属已经变化，请重新打开");
    }
    private static com.lingmao.scm.common.exception.BizException missing(){return new com.lingmao.scm.common.exception.BizException(404,"资料包不存在");}

    PackOwner style(long styleId) {
        return toOwner(styleService.packInfo(styleId));
    }

    /** 批量（按款看成本）；查不到的不在 map 里 */
    Map<Long, PackOwner> styles(Collection<Long> styleIds) {
        Map<Long, PackOwner> owners = new HashMap<>();
        styleService.packInfos(styleIds).forEach((id, info) -> owners.put(id, toOwner(info)));
        return owners;
    }

    /** 主图（PDF 表头）：款式图的第一张；没有是 null */
    AttachmentVO mainImage(TechPack pack) {
        if(pack.getStyleId()!=null)return attachmentService.firsts(BizType.STYLE,List.of(pack.getStyleId()),Attachment.IMAGE).get(pack.getStyleId());
        var identity=readIdentity(pack.getId());sameOwner(identity,pack);return sampleOwner.mainImage(identity.companyId(),identity.sampleId());
    }

    Ctx context(PackOwner owner) {
        return context(owner,false);
    }

    Ctx liveContext(TechPack pack) {
        return context(of(pack),pack.getStyleId()==null);
    }

    private Ctx context(PackOwner owner,boolean sample) {
        SizeGroupVO group = sample?sizeGroupService.getByIdsBounded(List.of(owner.sizeGroupId()),100).get(owner.sizeGroupId()):sizeGroupService.getById(owner.sizeGroupId());
        if(sample && (group==null || group.sizes().isEmpty() || !group.sizes().contains(group.baseSize())))
            throw new com.lingmao.scm.common.exception.BizException(400,"样衣尺码组没有有效基码");
        Map<Long, Color> colors = colorService.byIds(owner.colorIds());
        List<ColorOption> options = owner.colorIds().stream().map(colors::get).filter(Objects::nonNull)
                .map(ColorOption::from).toList();
        return new Ctx(owner, group.sizes(), group.baseSize(), options);
    }

    /** 读取版次：草稿实时；新生效版冻结；旧版保留已知停用色码并明确为 LEGACY。 */
    Ctx context(TechPack pack) {
        // 已生效快照也必须先验证当前真实 owner；不能用历史快照绕开后来改派/移交。
        if(pack.getStyleId()==null){var identity=readIdentity(pack.getId());sameOwner(identity,pack);}
        if (pack.getStatus() == PackStatus.DRAFT) return liveContext(pack);
        PackOwnerSnapshot frozen = pack.getOwnerSnapshot();
        if (frozen != null) return new Ctx(frozen.owner(), frozen.sizes(), frozen.baseSize(), frozen.colors());
        PackOwner live = of(pack);
        Ctx current = context(live,pack.getStyleId()==null);
        if(pack.getStyleId()==null)return current;
        StyleOrderInfo info = styleService.orderInfos(List.of(pack.getStyleId())).get(pack.getStyleId());
        if (info == null) return current;
        LinkedHashSet<Long> colorIds = new LinkedHashSet<>();
        info.colors().forEach(c -> colorIds.add(c.id()));
        colorIds.addAll(techPackMapper.referencedColors(pack.getId()));
        Map<Long, Color> known = colorService.byIds(colorIds);
        List<ColorOption> colors = colorIds.stream().map(known::get).filter(Objects::nonNull).map(ColorOption::from).toList();
        LinkedHashSet<String> sizes = new LinkedHashSet<>(info.sizes());
        info.skus().forEach(s -> sizes.add(s.sizeCode()));
        sizes.addAll(techPackMapper.referencedSizes(pack.getId()));
        sizes.remove(null);
        PackOwner legacy = new PackOwner(live.code(), live.name(), live.aliasName(), live.year(), live.season(),
                live.sampleNo(), live.sizeGroupId(), colors.stream().map(ColorOption::id).toList(), live.tagPrice(),
                live.defaultFactoryId(), live.productName(), live.composition(), live.lockedReason());
        return new Ctx(legacy, List.copyOf(sizes), current.baseSize(), colors);
    }

    /** 列表按批读取实时/旧版上下文；冻结版仍完全使用自己的快照。 */
    Map<Long, Ctx> contexts(Collection<TechPack> packs, Map<Long, PackOwner> owners) {
        Map<Long, Ctx> result = new HashMap<>();
        for(TechPack pack:packs)if(pack.getStyleId()==null)result.put(pack.getId(),context(pack));
        List<TechPack> livePacks = packs.stream().filter(p->p.getStyleId()!=null).filter(p -> p.getStatus() == PackStatus.DRAFT || p.getOwnerSnapshot() == null)
                .filter(p -> owners.containsKey(p.getStyleId())).toList();
        List<TechPack> legacy = livePacks.stream().filter(p -> p.getStatus() != PackStatus.DRAFT).toList();
        Map<Long, SizeGroupVO> groups = sizeGroupService.getByIds(livePacks.stream()
                .map(p -> owners.get(p.getStyleId()).sizeGroupId()).toList());
        Map<Long, StyleOrderInfo> infos = styleService.orderInfos(legacy.stream().map(TechPack::getStyleId).toList());
        List<Long> legacyIds = legacy.stream().map(TechPack::getId).toList();
        Map<Long, List<Long>> referencedColors = legacyIds.isEmpty() ? Map.of() : techPackMapper.referencedColorsBatch(legacyIds).stream()
                .collect(Collectors.groupingBy(PackColorRef::packId, Collectors.mapping(PackColorRef::colorId, Collectors.toList())));
        Map<Long, List<String>> referencedSizes = legacyIds.isEmpty() ? Map.of() : techPackMapper.referencedSizesBatch(legacyIds).stream()
                .collect(Collectors.groupingBy(PackSizeRef::packId, Collectors.mapping(PackSizeRef::sizeCode, Collectors.toList())));
        LinkedHashSet<Long> allColors = new LinkedHashSet<>();
        livePacks.forEach(p -> allColors.addAll(owners.get(p.getStyleId()).colorIds()));
        infos.values().forEach(info -> info.colors().forEach(c -> allColors.add(c.id())));
        referencedColors.values().forEach(allColors::addAll);
        Map<Long, Color> known = colorService.byIds(allColors);
        for (TechPack pack : packs) {
            if(pack.getStyleId()==null)continue;
            PackOwnerSnapshot frozen = pack.getOwnerSnapshot();
            if (pack.getStatus() != PackStatus.DRAFT && frozen != null) {
                result.put(pack.getId(), new Ctx(frozen.owner(), frozen.sizes(), frozen.baseSize(), frozen.colors()));
                continue;
            }
            PackOwner live = owners.get(pack.getStyleId());
            if (live == null) continue;
            SizeGroupVO group = groups.get(live.sizeGroupId());
            StyleOrderInfo info = pack.getStatus() == PackStatus.DRAFT ? null : infos.get(pack.getStyleId());
            if (info == null) {
                List<ColorOption> colors = live.colorIds().stream().map(known::get).filter(Objects::nonNull).map(ColorOption::from).toList();
                result.put(pack.getId(), new Ctx(live, group.sizes(), group.baseSize(), colors));
                continue;
            }
            LinkedHashSet<Long> colorIds = new LinkedHashSet<>();
            info.colors().forEach(c -> colorIds.add(c.id()));
            colorIds.addAll(referencedColors.getOrDefault(pack.getId(), List.of()));
            List<ColorOption> colors = colorIds.stream().map(known::get).filter(Objects::nonNull).map(ColorOption::from).toList();
            LinkedHashSet<String> sizes = new LinkedHashSet<>(info.sizes());
            info.skus().forEach(s -> sizes.add(s.sizeCode()));
            sizes.addAll(referencedSizes.getOrDefault(pack.getId(), List.of()));
            sizes.remove(null);
            PackOwner owner = new PackOwner(live.code(), live.name(), live.aliasName(), live.year(), live.season(),
                    live.sampleNo(), live.sizeGroupId(), colors.stream().map(ColorOption::id).toList(), live.tagPrice(),
                    live.defaultFactoryId(), live.productName(), live.composition(), live.lockedReason());
            result.put(pack.getId(), new Ctx(owner, List.copyOf(sizes), group.baseSize(), colors));
        }
        return result;
    }

    static PackOwnerSnapshot snapshot(Ctx context) {
        return new PackOwnerSnapshot(1, context.owner(), context.sizes(), context.baseSize(), context.colors());
    }

    static String contextSource(TechPack pack) {
        return pack.getStatus() == PackStatus.DRAFT ? "LIVE" : pack.getOwnerSnapshot() == null ? "LEGACY" : "SNAPSHOT";
    }

    private static PackOwner toOwner(StylePackInfo s) {
        String locked = s.status() == StyleStatus.DISABLED ? "款式 " + s.code() + " 已经停用了，资料包不能再建、改、生效" : null;
        return new PackOwner(s.code(), s.name(), s.aliasName(), s.year(), s.season(), s.sampleNo(), s.sizeGroupId(),
                s.colorIds(), s.tagPrice(), s.defaultFactoryId(), s.productName(), s.composition(), locked);
    }
}
