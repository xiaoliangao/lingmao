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

    /** 规则：二级要填编码（物料编码里用），存大写 */
    @Test
    void create_level2_codeRequiredAndUppercased() {
        when(materialCategoryMapper.selectById(1L)).thenReturn(category(1L, null, 1, null));

        assertThatThrownBy(() -> materialCategoryService.create(req(1L, " ", "面料")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("二级分类要填 2 位编码");

        when(materialCategoryMapper.exists(any())).thenReturn(false);
        materialCategoryService.create(req(1L, "ml", "面料"));

        ArgumentCaptor<MaterialCategory> saved = ArgumentCaptor.captor();
        verify(materialCategoryMapper).insert(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("ML");
        assertThat(saved.getValue().getLevel()).isEqualTo(2);
    }

    /**
     * 规则（RFC 018 §8.3）：编码能改，只影响以后新编的；同一个上级下不能重复（查重时带上级、排除自己），
     * 改出来的存大写
     */
    @Test
    void update_codeChangeable_uniqueUnderSameParent() {
        when(materialCategoryMapper.selectById(5L)).thenReturn(category(5L, 1L, 2, "ML"));
        when(materialCategoryMapper.exists(any())).thenReturn(true);

        assertThatThrownBy(() -> materialCategoryService.update(5L, req(1L, "03", "梭织")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("在这个上级下已经有了");
        verify(materialCategoryMapper, never()).update(any(), any());

        when(materialCategoryMapper.exists(any())).thenReturn(false);
        materialCategoryService.update(5L, req(1L, "03", "梭织"));
        ArgumentCaptor<MaterialCategory> saved = ArgumentCaptor.captor();
        verify(materialCategoryMapper).update(saved.capture(), any());
        assertThat(saved.getValue().getCode()).isEqualTo("03");
    }

    /** 规则：一级的编码选填：不填照样建，填了存大写、一级之间不重复 */
    @Test
    void create_level1_codeOptional() {
        materialCategoryService.create(req(null, null, "面料"));
        when(materialCategoryMapper.exists(any())).thenReturn(false);
        materialCategoryService.create(req(null, "ll", "里料"));

        ArgumentCaptor<MaterialCategory> saved = ArgumentCaptor.captor();
        verify(materialCategoryMapper, org.mockito.Mockito.times(2)).insert(saved.capture());
        assertThat(saved.getAllValues()).extracting(MaterialCategory::getCode).containsExactly(null, "LL");
        assertThat(saved.getAllValues()).extracting(MaterialCategory::getLevel).containsOnly(1);

        when(materialCategoryMapper.exists(any())).thenReturn(true);
        assertThatThrownBy(() -> materialCategoryService.create(req(null, "LL", "另一个")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已经有一级分类用了");
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
