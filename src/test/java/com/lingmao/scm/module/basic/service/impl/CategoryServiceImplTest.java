package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.dto.CategorySaveReq;
import com.lingmao.scm.module.basic.entity.Category;
import com.lingmao.scm.module.basic.mapper.CategoryMapper;
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
class CategoryServiceImplTest {

    @Mock
    private CategoryMapper categoryMapper;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Category.class);
    }

    /** 规则（PRD §6.1.2）：最多 3 级，第 3 级下面不能再加 */
    @Test
    void create_underLevel3_rejected() {
        when(categoryMapper.selectById(3L)).thenReturn(category(3L, 2L, 3, "衬衫", 1));

        assertThatThrownBy(() -> categoryService.create(req(3L, "长袖衬衫")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多 3 级");
        verify(categoryMapper, never()).insert(any(Category.class));
    }

    /** 规则：同一个上级下不能重名 */
    @Test
    void create_sameNameUnderSameParent_rejected() {
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, null, 1, "上装", 1));
        when(categoryMapper.exists(any())).thenReturn(true);

        assertThatThrownBy(() -> categoryService.create(req(1L, " 衬衫 ")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已经有「衬衫」了");
        verify(categoryMapper, never()).insert(any(Category.class));
    }

    /** 规则：级数 = 上级 + 1，名称去空格 */
    @Test
    void create_levelIsParentPlusOne() {
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, null, 1, "上装", 1));
        when(categoryMapper.exists(any())).thenReturn(false);

        categoryService.create(req(1L, " 衬衫 "));

        ArgumentCaptor<Category> saved = ArgumentCaptor.captor();
        verify(categoryMapper).insert(saved.capture());
        assertThat(saved.getValue().getLevel()).isEqualTo(2);
        assertThat(saved.getValue().getName()).isEqualTo("衬衫");
    }

    /** 规则：下面有子品类的不能删 */
    @Test
    void delete_hasChildren_rejected() {
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, null, 1, "上装", 1));
        when(categoryMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> categoryService.delete(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("下面还有 2 个子品类");
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    /** 规则：上级停用了，下面的也选不了（款式选品类时用） */
    @Test
    void checkSelectable_parentDisabled_rejected() {
        when(categoryMapper.selectById(2L)).thenReturn(category(2L, 1L, 2, "衬衫", 1));
        when(categoryMapper.selectById(1L)).thenReturn(category(1L, null, 1, "上装", 0));

        assertThatThrownBy(() -> categoryService.checkSelectable(2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("「上装」已经停用了");
    }

    private static Category category(Long id, Long parentId, int level, String name, int status) {
        Category c = new Category();
        c.setId(id);
        c.setParentId(parentId);
        c.setLevel(level);
        c.setName(name);
        c.setStatus(status);
        return c;
    }

    private static CategorySaveReq req(Long parentId, String name) {
        CategorySaveReq req = new CategorySaveReq();
        req.setParentId(parentId);
        req.setName(name);
        req.setSort(0);
        return req;
    }
}
