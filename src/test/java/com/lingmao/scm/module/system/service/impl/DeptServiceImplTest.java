package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.system.dto.DeptSaveReq;
import com.lingmao.scm.module.system.dto.DeptVO;
import com.lingmao.scm.module.system.entity.SysDept;
import com.lingmao.scm.module.system.entity.SysUserDept;
import com.lingmao.scm.module.system.mapper.SysDeptMapper;
import com.lingmao.scm.module.system.mapper.SysUserDeptMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeptServiceImplTest {

    @Mock
    private SysDeptMapper deptMapper;

    @Mock
    private SysUserDeptMapper userDeptMapper;

    @InjectMocks
    private DeptServiceImpl deptService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysDept.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserDept.class);
    }

    /** 规则：树按上下级套好，带"产品部/设计组"这样的路径和人数；同级按排序 */
    @Test
    void tree_nestedWithPathAndCount() {
        when(deptMapper.selectList(isNull())).thenReturn(List.of(dept(1, null, "产品部", 0), dept(3, 1L, "版房", 2),
                dept(2, 1L, "设计组", 1), dept(4, null, "采购部", 1)));
        when(userDeptMapper.countByDept()).thenReturn(List.of(new SysUserDeptMapper.DeptCount(2L, 5L)));

        List<DeptVO> tree = deptService.tree();

        assertThat(tree).extracting(DeptVO::name).containsExactly("产品部", "采购部");
        assertThat(tree.getFirst().children()).extracting(DeptVO::path).containsExactly("产品部/设计组", "产品部/版房");
        assertThat(tree.getFirst().children().getFirst().userCount()).isEqualTo(5);
    }

    /** 规则：不能挪到自己的下级下面（会成环）；也不能超过 5 级 */
    @Test
    void update_moveUnderOwnChildOrTooDeep_rejected() {
        // 1 > 2 > 3 > 4 > 5 是满 5 级的一枝；6 > 7 两级
        when(deptMapper.selectList(isNull())).thenReturn(List.of(dept(1, null, "一", 0), dept(2, 1L, "二", 0),
                dept(3, 2L, "三", 0), dept(4, 3L, "四", 0), dept(5, 4L, "五", 0), dept(6, null, "六", 0), dept(7, 6L, "七", 0)));

        assertThatThrownBy(() -> deptService.update(2, req(4L, "二")))
                .isInstanceOf(BizException.class).hasMessage("不能把部门挪到它自己或它的下级下面");
        assertThatThrownBy(() -> deptService.update(6, req(4L, "六")))         // 四在第 4 级，六带着七两级 → 6 级
                .isInstanceOf(BizException.class).hasMessageContaining("超过 5 级");
        assertThatThrownBy(() -> deptService.create(req(5L, "六级")))
                .isInstanceOf(BizException.class).hasMessageContaining("最多 5 级");
        verify(deptMapper, never()).update(any(SysDept.class), any());
        verify(deptMapper, never()).insert(any(SysDept.class));
    }

    /** 规则：有下级、有人的部门删不掉，报 1005 说清楚原因 */
    @Test
    void delete_withChildrenOrUsers_1005() {
        when(deptMapper.selectById(1L)).thenReturn(dept(1, null, "产品部", 0));
        when(deptMapper.selectList(any())).thenReturn(List.of(dept(2, 1L, "设计组", 0)));
        assertThatThrownBy(() -> deptService.delete(1))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM))
                .hasMessageContaining("设计组");

        when(deptMapper.selectById(2L)).thenReturn(dept(2, 1L, "设计组", 0));
        when(deptMapper.selectList(any())).thenReturn(List.of());
        when(userDeptMapper.selectCount(any())).thenReturn(3L);
        assertThatThrownBy(() -> deptService.delete(2))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM))
                .hasMessageContaining("3 个人");
        verify(deptMapper, never()).deleteById(any(Long.class));
    }

    private static SysDept dept(long id, Long parentId, String name, int sort) {
        SysDept d = new SysDept();
        d.setId(id);
        d.setParentId(parentId);
        d.setName(name);
        d.setSort(sort);
        return d;
    }

    private static DeptSaveReq req(Long parentId, String name) {
        DeptSaveReq req = new DeptSaveReq();
        req.setParentId(parentId);
        req.setName(name);
        req.setSort(0);
        return req;
    }
}
