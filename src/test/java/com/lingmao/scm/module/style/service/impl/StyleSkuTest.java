package com.lingmao.scm.module.style.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.style.dto.StyleCreateResult;
import com.lingmao.scm.module.style.dto.StyleSaveReq;
import com.lingmao.scm.module.style.entity.Style;
import com.lingmao.scm.module.style.entity.StyleColor;
import com.lingmao.scm.module.style.entity.StyleSku;
import com.lingmao.scm.module.style.entity.StyleStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 颜色 × 尺码生成 SKU（RFC 005 §5、§12 StyleSkuTest；PRD §6.2 验收 2、6 前半）
 */
@ExtendWith(MockitoExtension.class)
class StyleSkuTest extends StyleTestBase {

    /** 规则：建款时带了卡其、藏青、黑三色，尺码组 S–XXL：按颜色顺序、尺码顺序生成 15 个，SKU 码 = 款号-颜色码-尺码 */
    @Test
    void create_threeColorsFiveSizes_fifteenSkus() {
        serialFrom(new HashMap<>());
        codesTaken();

        StyleCreateResult result = service.create(autoReq(KQ.getId(), ZQ.getId(), BK.getId()));

        assertThat(result.code()).isEqualTo("FS2611080701");
        assertThat(result.skuCount()).isEqualTo(15);
        List<StyleSku> skus = insertedSkus();
        assertThat(skus).hasSize(15);
        assertThat(skus).extracting(StyleSku::getCode).startsWith(
                "FS2611080701-KQ-S", "FS2611080701-KQ-M", "FS2611080701-KQ-L", "FS2611080701-KQ-XL", "FS2611080701-KQ-XXL",
                "FS2611080701-ZQ-S").contains("FS2611080701-KQ-M").endsWith("FS2611080701-BK-XXL");
        ArgumentCaptor<StyleColor> colors = ArgumentCaptor.captor();
        verify(styleColorMapper, times(3)).insert(colors.capture());
        assertThat(colors.getAllValues()).extracting(StyleColor::getColorId, StyleColor::getSeq)
                .containsExactly(tuple(KQ.getId(), 1), tuple(ZQ.getId(), 2),
                        tuple(BK.getId(), 3));
    }

    /** 规则：已经有 3 色 × 5 码，追加酒红再点生成：只补酒红的 5 个，已有的跳过（调几次结果都一样） */
    @Test
    void generate_afterAddingColor_onlyMissingOnes() {
        Style s = style(5L, "FS2611080701", StyleStatus.DRAFT);
        when(styleMapper.lockById(5L)).thenReturn(5L);
        when(styleMapper.selectById(5L)).thenReturn(s);
        when(styleColorMapper.selectList(any())).thenReturn(List.of(
                styleColor(5L, KQ, 1), styleColor(5L, ZQ, 2), styleColor(5L, BK, 3), styleColor(5L, JH, 4)));
        List<StyleSku> existing = new ArrayList<>();
        for (var c : List.of(KQ, ZQ, BK)) {
            SIZES.forEach(size -> existing.add(sku(5L, s.getCode(), c, size)));
        }
        when(styleSkuMapper.selectList(any())).thenReturn(existing);

        int created = service.generateSkus(5L);

        assertThat(created).isEqualTo(5);
        assertThat(insertedSkus()).extracting(StyleSku::getCode).containsExactly(
                "FS2611080701-JH-S", "FS2611080701-JH-M", "FS2611080701-JH-L", "FS2611080701-JH-XL", "FS2611080701-JH-XXL");
    }

    /** 规则：已经生成了 SKU 的款式不能换尺码组（PRD 说"有订单以后"，款式查不到订单，提早到"有 SKU 以后"） */
    @Test
    void update_changeSizeGroupWithSkus_rejected() {
        Style s = style(5L, "FS2611080701", StyleStatus.DRAFT);
        when(styleMapper.lockById(5L)).thenReturn(5L);
        when(styleMapper.selectById(5L)).thenReturn(s);
        when(styleSkuMapper.exists(any())).thenReturn(true);
        StyleSaveReq req = fillReq(new StyleSaveReq());
        req.setSizeGroupId(OTHER_SIZE_GROUP);

        assertThatThrownBy(() -> service.update(5L, req))
                .isInstanceOf(BizException.class)
                .hasMessageStartingWith("已生成 SKU，不能换尺码组");
        verify(styleMapper, never()).update(any(Style.class), any());
    }

    /** 没有启用的颜色时生成报错，不发空的 INSERT */
    @Test
    void generate_noEnabledColor_rejected() {
        when(styleMapper.lockById(5L)).thenReturn(5L);
        when(styleMapper.selectById(5L)).thenReturn(style(5L, "FS2611080701", StyleStatus.DRAFT));
        StyleColor off = styleColor(5L, KQ, 1);
        off.setStatus(0);
        when(styleColorMapper.selectList(any())).thenReturn(List.of(off));

        assertThatThrownBy(() -> service.generateSkus(5L)).hasMessageStartingWith("还没有启用的颜色");
        verify(styleSkuMapper, never()).insertMissing(any(), any(), any());
    }

    private List<StyleSku> insertedSkus() {
        ArgumentCaptor<List<StyleSku>> captor = ArgumentCaptor.captor();
        verify(styleSkuMapper).insertMissing(captor.capture(), eq(1L), any());
        return captor.getValue();
    }
}
