package com.lingmao.scm.module.style.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.basic.entity.Category;
import com.lingmao.scm.module.system.dto.CodeSegment;
import com.lingmao.scm.module.system.dto.CodeTarget;
import com.lingmao.scm.module.system.dto.DictOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 款号（RFC 005 §3、§12 StyleCodeTest；PRD §6.2 验收 1、4 前半）。"今天"是 2026-11-08，日期段 261108
 */
@ExtendWith(MockitoExtension.class)
class StyleCodeTest extends StyleTestBase {

    /**
     * 规则：大类 + yyMMdd + 设计师编号 + 2 位流水。编号 07 的设计师先后两款得到 01、02；编号 12 的另起流水得到 01。
     * 07 第一次用来编号时锁住它（12 已经锁过，不再锁）
     */
    @Test
    void take_designerNumberAndDailySerial() {
        serialFrom(new HashMap<>());
        codesTaken();

        assertThat(codes.take(in("FS", DESIGNER_07))).isEqualTo("FS2611080701");
        assertThat(codes.take(in("FS", DESIGNER_07))).isEqualTo("FS2611080702");
        assertThat(codes.take(in("FS", DESIGNER_12))).isEqualTo("FS2611081201");
        verify(userService, times(2)).lockDesignerNo(DESIGNER_07, "07");
        verify(userService, never()).lockDesignerNo(DESIGNER_12, "12");
    }

    /** 规则：取到的号已经被手填的款式占了就再取下一个；预览也跳过它，而且只看不取、不锁编号 */
    @Test
    void takeAndPreview_skipManuallyTakenCode() {
        Map<String, Integer> serial = new HashMap<>(Map.of("FS26110807", 2));
        serialFrom(serial);
        codesTaken("FS2611080703");

        assertThat(codes.preview(in("FS", DESIGNER_07))).isEqualTo("FS2611080704");
        assertThat(serial.get("FS26110807")).isEqualTo(2);                      // 预览没动流水
        verify(userService, never()).lockDesignerNo(anyLong(), anyString());

        assertThat(codes.take(in("FS", DESIGNER_07))).isEqualTo("FS2611080704");
    }

    /** 规则：设计师没有编号编不了，提示去管理后台填或者手填（PRD §6.2 规则 5） */
    @Test
    void take_designerWithoutNumber_rejected() {
        assertThatThrownBy(() -> codes.take(in("FS", DESIGNER_NONE)))
                .isInstanceOf(BizException.class)
                .hasMessage("设计师丙某还没有设计师编号，请超级管理员在 管理后台 › 用户 里填；也可以手填款号")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
        verify(serialNoGenerator, never()).nextSeq(anyString());
    }

    /** 规则：一个设计师一天最多 99 款，超过了报 1000、提示手填；预览同样 */
    @Test
    void take_over99_rejected() {
        serialFrom(new HashMap<>(Map.of("FS26110807", 99)));
        codesTaken();

        assertThatThrownBy(() -> codes.take(in("FS", DESIGNER_07)))
                .isInstanceOf(BizException.class)
                .hasMessage(StyleCodes.USED_UP)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
        assertThatThrownBy(() -> codes.preview(in("FS", DESIGNER_07))).hasMessage(StyleCodes.USED_UP);
    }

    /** 规则：手填转大写、3-20 位字母数字横杠；已有的报"款号重复" */
    @Test
    void manual_upperFormatAndDuplicate() {
        codesTaken("27CF001");

        assertThat(codes.manual(" fs-2701 ")).isEqualTo("FS-2701");
        assertThatThrownBy(() -> codes.manual("27cf001")).hasMessage(StyleCodes.DUPLICATE);
        assertThatThrownBy(() -> codes.manual("款号 1"))
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(400);
    }

    /**
     * 规则（RFC 018 §8）：甲方 11-25 的款号规则 F + 品牌编码 + 年份 + 设计师 + Z + 品类编码 + - + 3 位流水。
     * 品牌编码取字典「品牌」上填的编码（导入时建的品牌值是中文名）；没填编码的中文品牌报清楚去哪填
     */
    @Test
    void take_clientRule1125_brandCodeFromDict() {
        serialFrom(new HashMap<>());
        codesTaken();
        StyleCodes client = new StyleCodes(styleMapper, serialNoGenerator, userService, com.lingmao.scm.TestCodeRules.with(CLOCK,
                CodeTarget.STYLE, List.of(text("F"), seg(CodeSegment.Type.BRAND), seg(CodeSegment.Type.STYLE_YEAR),
                        seg(CodeSegment.Type.DESIGNER), text("Z"), seg(CodeSegment.Type.CATEGORY), text("-"),
                        new CodeSegment(CodeSegment.Type.SEQ, null, null, 3))), categoryService, dicts);
        when(dictDataService.listByType("brand")).thenReturn(List.of(
                new DictOption("测试品牌", "测试品牌", 1, "S"), new DictOption("没编码的品牌", "没编码的品牌", 1)));
        Category leaf = new Category();
        leaf.setId(77L);
        leaf.setName("长裤");
        leaf.setCode("33");
        when(categoryService.getById(77L)).thenReturn(leaf);

        assertThat(client.take(new StyleCodes.Input("FS", DESIGNER_07, "测试品牌", 77L, 2026))).isEqualTo("FS2607Z33-001");
        assertThat(client.take(new StyleCodes.Input("TS", DESIGNER_07, "测试品牌", 77L, 2026))).isEqualTo("FS2607Z33-002");
        assertThatThrownBy(() -> client.take(new StyleCodes.Input("FS", DESIGNER_07, "没编码的品牌", 77L, 2026)))
                .isInstanceOf(BizException.class).hasMessageContaining("还没填编码").hasMessageContaining("字典管理");
    }

    private static CodeSegment seg(CodeSegment.Type type) {
        return new CodeSegment(type, null, null, null);
    }

    private static CodeSegment text(String text) {
        return new CodeSegment(CodeSegment.Type.TEXT, text, null, null);
    }

    private static StyleCodes.Input in(String styleClass, Long designerId) {
        return new StyleCodes.Input(styleClass, designerId, null, null, null);
    }
}
