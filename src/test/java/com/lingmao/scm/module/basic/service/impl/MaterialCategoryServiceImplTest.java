package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.dto.MaterialCategorySaveReq;
import com.lingmao.scm.module.basic.entity.MaterialCategory;
import com.lingmao.scm.module.basic.mapper.MaterialCategoryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialCategoryServiceImplTest {

    @Mock
    private MaterialCategoryMapper materialCategoryMapper;

    @InjectMocks
    private MaterialCategoryServiceImpl materialCategoryService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MaterialCategory.class);
    }

    /** 规则（PRD §6.1.5）：只有两级，二级下面不能再加 */
    @Test
    void create_underLevel2_rejected() {
        when(materialCategoryMapper.selectById(5L)).thenReturn(category(5L, 1L, 2, "ML"));

        assertThatThrownBy(() -> materialCategoryService.create(req(5L, "XX", "再下一级")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有两级");
        verify(materialCategoryMapper, never()).insert(any(MaterialCategory.class));
    }

    /** 规则：二级要填编码（物料编码的前缀），存大写 */
    @Test
    void create_level2_codeRequiredAndUppercased() {
        when(materialCategoryMapper.selectById(1L)).thenReturn(category(1L, null, 1, null));

        assertThatThrownBy(() -> materialCategoryService.create(req(1L, " ", "面料")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("二级分类要填 2 位字母的编码");

        when(materialCategoryMapper.exists(any())).thenReturn(false);
        materialCategoryService.create(req(1L, "ml", "面料"));

        ArgumentCaptor<MaterialCategory> saved = ArgumentCaptor.captor();
        verify(materialCategoryMapper).insert(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("ML");
        assertThat(saved.getValue().getLevel()).isEqualTo(2);
    }

    /** 规则：二级的编码建好不能改 */
    @Test
    void update_codeChanged_rejected() {
        when(materialCategoryMapper.selectById(5L)).thenReturn(category(5L, 1L, 2, "ML"));

        assertThatThrownBy(() -> materialCategoryService.update(5L, req(1L, "FL", "面料")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分类编码建好就不能改");
        verify(materialCategoryMapper, never()).update(any(), any());
    }

    private static MaterialCategory category(Long id, Long parentId, int level, String code) {
        MaterialCategory c = new MaterialCategory();
        c.setId(id);
        c.setParentId(parentId);
        c.setLevel(level);
        c.setCode(code);
        c.setName("分类" + id);
        c.setStatus(1);
        return c;
    }

    private static MaterialCategorySaveReq req(Long parentId, String code, String name) {
        MaterialCategorySaveReq req = new MaterialCategorySaveReq();
        req.setParentId(parentId);
        req.setCode(code);
        req.setName(name);
        req.setSort(0);
        return req;
    }
}
