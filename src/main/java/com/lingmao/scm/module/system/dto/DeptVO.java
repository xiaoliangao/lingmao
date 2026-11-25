package com.lingmao.scm.module.system.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门树的一个节点（RFC 018 §1.5）。userCount 是直属的人数（不含下级部门的），children 没有下级时是空数组
 *
 * @param path 全路径，如"产品部/设计组"：选部门的下拉、导入找部门、列表上重名时用
 */
public record DeptVO(Long id, Long parentId, String name, Integer sort, String path, long userCount, List<DeptVO> children) {

    public DeptVO(Long id, Long parentId, String name, Integer sort, String path, long userCount) {
        this(id, parentId, name, sort, path, userCount, new ArrayList<>());
    }
}
