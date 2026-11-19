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
 * 主人都是款式（甲方 11-18 答 Q24：研发不填物料清单，样衣不挂资料包）
 */
@Component
@RequiredArgsConstructor
class PackOwners {

    private final StyleService styleService;
    private final SizeGroupService sizeGroupService;
    private final ColorService colorService;
    private final AttachmentService attachmentService;
    private final TechPackMapper techPackMapper;

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
        return style(pack.getStyleId());
    }

    PackOwner style(long styleId) {
        return toOwner(styleService.packInfo(styleId));
    }

    /** 款式在不在、我能不能看（品类线，RFC 013 §2） */
    boolean visible(long styleId) {
        return styleService.visible(styleId);
    }

    /** 批量（按款看成本）；查不到的不在 map 里 */
    Map<Long, PackOwner> styles(Collection<Long> styleIds) {
        Map<Long, PackOwner> owners = new HashMap<>();
        styleService.packInfos(styleIds).forEach((id, info) -> owners.put(id, toOwner(info)));
        return owners;
    }

    /** 主图（PDF 表头）：款式图的第一张；没有是 null */
    AttachmentVO mainImage(TechPack pack) {
        return attachmentService.firsts(BizType.STYLE, List.of(pack.getStyleId()), Attachment.IMAGE).get(pack.getStyleId());
    }

    Ctx context(PackOwner owner) {
        SizeGroupVO group = sizeGroupService.getById(owner.sizeGroupId());
        Map<Long, Color> colors = colorService.byIds(owner.colorIds());
        List<ColorOption> options = owner.colorIds().stream().map(colors::get).filter(Objects::nonNull)
                .map(ColorOption::from).toList();
        return new Ctx(owner, group.sizes(), group.baseSize(), options);
    }

    /** 读取版次：草稿实时；新生效版冻结；旧版保留已知停用色码并明确为 LEGACY。 */
    Ctx context(TechPack pack) {
        if (pack.getStatus() == PackStatus.DRAFT) return context(of(pack));
        PackOwnerSnapshot frozen = pack.getOwnerSnapshot();
        if (frozen != null) return new Ctx(frozen.owner(), frozen.sizes(), frozen.baseSize(), frozen.colors());
        PackOwner live = of(pack);
        Ctx current = context(live);
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
        List<TechPack> livePacks = packs.stream().filter(p -> p.getStatus() == PackStatus.DRAFT || p.getOwnerSnapshot() == null)
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
