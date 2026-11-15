package com.lingmao.scm.module.style.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.style.entity.Style;
import com.lingmao.scm.module.style.entity.StyleSku;
import com.lingmao.scm.module.style.mapper.StyleSkuMapper;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一键生成 SKU（RFC 005 §5）：颜色 × 尺码，按颜色顺序、尺码顺序，已有的跳过。建款、加颜色后生成、导入都用它。
 * SKU 码 = 款号-颜色码-尺码（FS2611080701-KQ-M），最长 32 + 1 + 3 + 1 + 16 = 53 位，放得进 64
 */
@Component
@RequiredArgsConstructor
class SkuGenerator {

    private final StyleSkuMapper styleSkuMapper;
    private final Clock clock;

    static String skuCode(String styleCode, String colorCode, String sizeCode) {
        return styleCode + "-" + colorCode + "-" + sizeCode;
    }

    /**
     * 生成缺的 SKU，返回新建的 SKU 码。先查已有的，只插缺的；插入本身也带 ON CONFLICT DO NOTHING，
     * 调用方没锁款式时两个人同时点也只建一份。SKU 码和别的款式的撞了（导入的老款号里带横杠时可能）→ 1000
     */
    List<String> generate(Style style, List<Color> colors, List<String> sizes) {
        Set<String> existing = styleSkuMapper.selectList(new LambdaQueryWrapper<StyleSku>()
                        .select(StyleSku::getColorId, StyleSku::getSizeCode)
                        .eq(StyleSku::getStyleId, style.getId()))
                .stream().map(k -> k.getColorId() + "|" + k.getSizeCode()).collect(Collectors.toSet());
        List<StyleSku> rows = new ArrayList<>();
        for (Color color : colors) {
            for (String size : sizes) {
                if (existing.contains(color.getId() + "|" + size)) {
                    continue;
                }
                StyleSku sku = new StyleSku();
                sku.setStyleId(style.getId());
                sku.setColorId(color.getId());
                sku.setSizeCode(size);
                sku.setCode(skuCode(style.getCode(), color.getCode(), size));
                rows.add(sku);
            }
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        try {
            styleSkuMapper.insertMissing(rows, LoginUser.currentUserId(), OffsetDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            throw new BizException("要生成的 SKU 码和别的款式的 SKU 撞了（款号里带横杠时会这样），请联系管理员");
        }
        return rows.stream().map(StyleSku::getCode).toList();
    }
}
