package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.module.system.entity.SysMenu;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MenuVOTest {

    /** 规则：平铺列表按 parentId 挂成树，顺序保持；上级不在列表里的节点被丢掉 */
    @Test
    void tree_buildsNestedChildrenAndDropsOrphans() {
        List<SysMenu> flat = List.of(
                menu(1L, 0L, "系统管理"),
                menu(2L, 1L, "用户管理"),
                menu(3L, 2L, "新增"),
                menu(4L, 1L, "角色管理"),
                menu(5L, 99L, "上级被停用的菜单"));

        List<MenuVO> tree = MenuVO.tree(flat);

        assertThat(tree).extracting(MenuVO::name).containsExactly("系统管理");
        assertThat(tree.get(0).children()).extracting(MenuVO::name).containsExactly("用户管理", "角色管理");
        assertThat(tree.get(0).children().get(0).children()).extracting(MenuVO::name).containsExactly("新增");
    }

    private SysMenu menu(Long id, Long parentId, String name) {
        SysMenu m = new SysMenu();
        m.setId(id);
        m.setParentId(parentId);
        m.setName(name);
        return m;
    }
}
