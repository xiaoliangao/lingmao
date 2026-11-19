package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.dto.ColorSaveReq;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.mapper.ColorMapper;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ColorServiceImplTest {

    @Mock
    private ColorMapper colorMapper;

    @Mock
    private DictDataService dictDataService;

    private ColorServiceImpl colorService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Color.class);
    }

    @BeforeEach
    void setUp() {
        colorService = new ColorServiceImpl(colorMapper, new DictChecks(dictDataService));
    }

    /** 规则：编码存大写；色系要是字典里启用的 */
    @Test
    void create_codeUppercased_familyChecked() {
        when(colorMapper.exists(any())).thenReturn(false);
        when(dictDataService.listByType("color_family")).thenReturn(List.of(new DictOption("黑白灰", "BW", 1)));

        colorService.create(req("he", "黑", "BW"));

        ArgumentCaptor<Color> saved = ArgumentCaptor.captor();
        verify(colorMapper).insert(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("HE");
        assertThat(saved.getValue().getHex()).isEqualTo("#1A1A1A");

        assertThatThrownBy(() -> colorService.create(req("BA", "白", "NOPE")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("色系「NOPE」不在字典里");
    }

    /** 规则：新建的颜色编码只能 2 位字母（甲方 11-18，RFC 012 §5.3）；3 位、带数字的是领猫的老编码，只能导入 */
    @Test
    void create_codeMustBeTwoLetters() {
        for (String bad : List.of("KQA", "K1", "12")) {
            assertThatThrownBy(() -> colorService.create(req(bad, "卡其", null)))
                    .isInstanceOf(BizException.class).hasMessageContaining("2 位字母");
        }
        verify(colorMapper, never()).insert(any(Color.class));
    }

    /** 规则：颜色编码建好不能改（进了 SKU 码） */
    @Test
    void update_codeChanged_rejected() {
        Color old = new Color();
        old.setId(1L);
        old.setCode("HE");
        when(colorMapper.selectById(1L)).thenReturn(old);

        assertThatThrownBy(() -> colorService.update(1L, req("HS", "黑", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("颜色编码建好就不能改");
        verify(colorMapper, never()).update(any(), any());
    }

    /** 规则：名称不能重复 */
    @Test
    void create_nameTaken_rejected() {
        when(colorMapper.exists(any())).thenReturn(false, true);   // 编码没占，名称占了

        assertThatThrownBy(() -> colorService.create(req("HE", "黑", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("「黑」已经有了");
        verify(colorMapper, never()).insert(any(Color.class));
    }

    private static ColorSaveReq req(String code, String name, String family) {
        ColorSaveReq req = new ColorSaveReq();
        req.setCode(code);
        req.setName(name);
        req.setFamily(family);
        req.setHex("#1a1a1a");
        return req;
    }
}
