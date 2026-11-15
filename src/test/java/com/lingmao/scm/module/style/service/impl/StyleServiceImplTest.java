package com.lingmao.scm.module.style.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.style.dto.StyleCreateReq;
import com.lingmao.scm.module.style.dto.StyleRef;
import com.lingmao.scm.module.style.dto.StyleSaveReq;
import com.lingmao.scm.module.style.entity.Style;
import com.lingmao.scm.module.style.entity.StyleStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 款式的保存、状态、给别的域的方法（RFC 005 §4、§6、§12 StyleServiceImplTest；PRD §6.2 验收 3、4 后半）
 */
@ExtendWith(MockitoExtension.class)
class StyleServiceImplTest extends StyleTestBase {

    /** 规则：款号建好不改，换设计师也不变：保存不取号，整条写回时 code 列不在 SET 里 */
    @Test
    void update_changeDesigner_codeUnchanged() {
        Style s = style(5L, "FS2611080701", StyleStatus.ENABLED);
        when(styleMapper.lockById(5L)).thenReturn(5L);
        when(styleMapper.selectById(5L)).thenReturn(s);
        StyleSaveReq req = fillReq(new StyleSaveReq());
        req.setDesignerId(DESIGNER_12);

        service.update(5L, req);

        ArgumentCaptor<Style> entity = ArgumentCaptor.captor();
        ArgumentCaptor<UpdateWrapper<Style>> wrapper = ArgumentCaptor.captor();
        verify(styleMapper).update(entity.capture(), wrapper.capture());
        assertThat(entity.getValue().getDesignerId()).isEqualTo(DESIGNER_12);
        assertThat(entity.getValue().getCode()).isEqualTo("FS2611080701");
        assertThat(wrapper.getValue().getSqlSet()).doesNotContain("code").doesNotContain("status");
        verify(serialNoGenerator, never()).next(anyString(), anyInt());
        verify(userService, never()).lockDesignerNo(anyLong(), anyString());
    }

    /** 规则：手填的款号已经有了 → 1000"款号重复"，不插入 */
    @Test
    void create_manualCodeExists_duplicate() {
        codesTaken("27CF001");
        StyleCreateReq req = fillReq(new StyleCreateReq());
        req.setCodeMode(StyleCreateReq.CodeMode.MANUAL);
        req.setCode("27CF001");

        BizException e = catchThrowableOfType(BizException.class, () -> service.create(req));

        assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
        assertThat(e.getMessage()).isEqualTo("款号重复");
        verify(styleMapper, never()).insert(any(Style.class));
    }

    /** 规则：没有启用的 SKU 不能启用（PRD §6.2 验收 3）；状态没改 */
    @Test
    void enable_withoutSku_rejected() {
        when(styleMapper.selectById(5L)).thenReturn(style(5L, "FS2611080701", StyleStatus.DRAFT));
        when(styleSkuMapper.exists(any())).thenReturn(false);

        assertThatThrownBy(() -> service.enable(5L))
                .isInstanceOf(BizException.class)
                .hasMessageStartingWith("还没有启用的 SKU，不能启用");
        verify(styleMapper, never()).update(any(Style.class), any());
    }

    /** 规则：状态照转换表走、比较并交换：草稿不能停用（1002）；条件更新 0 行（被别人改了）也是 1002 */
    @Test
    void stateActions_followFlowAndCas() {
        when(styleMapper.selectById(5L)).thenReturn(style(5L, "FS2611080701", StyleStatus.DRAFT));
        assertThat(catchThrowableOfType(BizException.class, () -> service.disable(5L)).getCode()).isEqualTo(ErrorCodes.STATE_CHANGED);

        when(styleSkuMapper.exists(any())).thenReturn(true);
        when(styleMapper.update(any(Style.class), any())).thenReturn(0);
        assertThat(catchThrowableOfType(BizException.class, () -> service.enable(5L)).getCode()).isEqualTo(ErrorCodes.STATE_CHANGED);

        when(styleMapper.update(any(Style.class), any())).thenReturn(1);
        service.enable(5L);
        verify(docLogService).add(any(), eq(5L), eq("enable"),
                eq(StyleStatus.DRAFT), eq(StyleStatus.ENABLED), any());
    }

    /** 规则：TRADE 关时来源"客户来样"、客户、客户款号一律 400（RFC 003 §3.3） */
    @Test
    void create_tradeOff_customerSampleRejected() {
        StyleCreateReq req = autoReq();
        req.setSource("CUSTOMER_SAMPLE");

        BizException e = catchThrowableOfType(BizException.class, () -> service.create(req));

        assertThat(e.getCode()).isEqualTo(400);
        assertThat(e.getMessage()).contains("来源「客户来样」是贸易代工用的");

        StyleCreateReq withCustomer = autoReq();
        withCustomer.setCustomerStyleNo("KH-001");
        assertThat(catchThrowableOfType(BizException.class, () -> service.create(withCustomer)).getCode()).isEqualTo(400);
    }

    /** 规则：品类要选启用的最末一级，规则在 basic 的 CategoryService.checkSelectable，款式只调它 */
    @Test
    void create_categoryNotLeaf_rejected() {
        doThrow(new BizException("品类「测试上衣」下面还有子品类，请选到最末一级")).when(categoryService).checkSelectable(CAT_TOP);
        StyleCreateReq req = autoReq();
        req.setCategoryId(CAT_TOP);

        assertThatThrownBy(() -> service.create(req)).hasMessage("品类「测试上衣」下面还有子品类，请选到最末一级");
        verify(styleMapper, never()).insert(any(Style.class));
    }

    /** 规则：字典列新选的要启用；修改时没动的旧值，字典项停用了也照收 */
    @Test
    void dictValues_newMustBeEnabled_oldKept() {
        StyleCreateReq req = autoReq();
        req.setBrand("NOPE");
        assertThat(catchThrowableOfType(BizException.class, () -> service.create(req)))
                .hasMessage("品牌「NOPE」不在字典里，或者已经停用了");

        Style s = style(5L, "FS2611080701", StyleStatus.ENABLED);
        s.setBrand("OLD_BRAND");                                     // 字典里已经没有了
        when(styleMapper.lockById(5L)).thenReturn(5L);
        when(styleMapper.selectById(5L)).thenReturn(s);
        StyleSaveReq update = fillReq(new StyleSaveReq());
        update.setBrand("OLD_BRAND");
        service.update(5L, update);
        verify(styleMapper).update(any(Style.class), any());
    }

    /** 给价格档案：启用、草稿的能录（打样阶段就报价）；停用 1000 说清是什么状态；不存在 404 */
    @Test
    void checkUsable_disabledRejected() {
        when(styleMapper.selectById(5L)).thenReturn(style(5L, "FS2611080701", StyleStatus.ENABLED));
        when(styleMapper.selectById(6L)).thenReturn(style(6L, "FS2611080702", StyleStatus.DRAFT));
        when(styleMapper.selectById(7L)).thenReturn(style(7L, "FS2611080703", StyleStatus.DISABLED));

        service.checkUsable(5L);
        service.checkUsable(6L);
        assertThat(catchThrowableOfType(BizException.class, () -> service.checkUsable(7L)))
                .hasMessage("款式 FS2611080703 现在是「停用」，不能再用")
                .extracting(BizException::getCode).isEqualTo(ErrorCodes.BIZ_ERROR);
        assertThat(catchThrowableOfType(BizException.class, () -> service.checkUsable(8L)).getCode()).isEqualTo(404);
    }

    /** 给价格档案：一批 id → 款号、名称；空的不查库 */
    @Test
    void refs_batch() {
        Style a = style(5L, "FS2611080701", StyleStatus.ENABLED);
        Style b = style(6L, "27CF001", StyleStatus.DISABLED);
        when(styleMapper.selectList(any())).thenReturn(List.of(a, b));

        Map<Long, StyleRef> refs = service.refs(Arrays.asList(5L, 6L, null, 5L));

        assertThat(refs).containsEntry(5L, new StyleRef(5L, "FS2611080701", "测试风衣"))
                .containsEntry(6L, new StyleRef(6L, "27CF001", "测试风衣"));
        assertThat(service.refs(List.of())).isEmpty();
        // 物料价那几行 styleId 为空，调用方直接 get(null)：不能抛
        assertThat(service.refs(List.of()).get(null)).isNull();
        verify(styleMapper).selectList(any());
    }

    /** 按钮由后端算：转换表允许的 ∩ 有权限的 */
    @Test
    void actions_flowAndPermissions() {
        loginAs(1L, "list", "edit");
        assertThat(StyleServiceImpl.actions(style(5L, "X", StyleStatus.DRAFT))).containsExactly("enable", "edit");
        loginAs(1L, "list", "edit", "remove");
        assertThat(StyleServiceImpl.actions(style(5L, "X", StyleStatus.ENABLED))).containsExactly("disable", "edit", "remove");
        loginAs(1L, "list");
        assertThat(StyleServiceImpl.actions(style(5L, "X", StyleStatus.ENABLED))).isEmpty();
    }
}
