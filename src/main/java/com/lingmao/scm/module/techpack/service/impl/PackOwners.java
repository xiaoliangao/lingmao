package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.service.ColorService;
import com.lingmao.scm.module.basic.service.SizeGroupService;
import com.lingmao.scm.module.style.dto.StylePackInfo;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.techpack.dto.PackOwner;
import com.lingmao.scm.module.techpack.entity.TechPack;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /** 批量（按款看成本）；查不到的不在 map 里 */
    Map<Long, PackOwner> styles(Collection<Long> styleIds) {
        Map<Long, PackOwner> owners = new HashMap<>();
        styleService.packInfos(styleIds).forEach((id, info) -> owners.put(id, toOwner(info)));
        return owners;
    }

    Ctx context(PackOwner owner) {
        SizeGroupVO group = sizeGroupService.getById(owner.sizeGroupId());
        Map<Long, Color> colors = colorService.byIds(owner.colorIds());
        List<ColorOption> options = owner.colorIds().stream().map(colors::get).filter(Objects::nonNull)
                .map(ColorOption::from).toList();
        return new Ctx(owner, group.sizes(), group.baseSize(), options);
    }

    private static PackOwner toOwner(StylePackInfo s) {
        String locked = s.status() == StyleStatus.DISABLED ? "款式 " + s.code() + " 已经停用了，资料包不能再建、改、生效" : null;
        return new PackOwner(s.code(), s.name(), s.aliasName(), s.year(), s.season(), s.sampleNo(), s.sizeGroupId(),
                s.colorIds(), s.tagPrice(), s.defaultFactoryId(), locked);
    }
}
