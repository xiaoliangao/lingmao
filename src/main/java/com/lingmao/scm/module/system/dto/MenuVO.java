package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.module.system.entity.SysMenu;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 菜单树的一个节点，children 是它的下级 */
public record MenuVO(Long id,
                     Long parentId,
                     String name,
                     Integer type,
                     String path,
                     String component,
                     String perms,
                     String icon,
                     Integer sort,
                     Boolean visible,
                     Integer status,
                     Boolean adminOnly,
                     List<MenuVO> children) {

    /**
     * 平铺的菜单列表 → 树。menus 要事先按 sort 排好序。
     * 先按 parentId 分组，再从顶层（parentId = 0）开始，递归地给每个节点挂上它的下级。
     * 上级不在列表里的节点（比如上级被停用了）会被丢掉。
     */
    public static List<MenuVO> tree(List<SysMenu> menus) {
        Map<Long, List<SysMenu>> byParent = menus.stream()
                .collect(Collectors.groupingBy(SysMenu::getParentId, LinkedHashMap::new, Collectors.toList()));
        return children(0L, byParent);
    }

    private static List<MenuVO> children(Long parentId, Map<Long, List<SysMenu>> byParent) {
        return byParent.getOrDefault(parentId, List.of()).stream()
                .map(m -> new MenuVO(m.getId(), m.getParentId(), m.getName(), m.getType(), m.getPath(),
                        m.getComponent(), m.getPerms(), m.getIcon(), m.getSort(), m.getVisible(), m.getStatus(),
                        m.getAdminOnly(), children(m.getId(), byParent)))
                .toList();
    }
}
