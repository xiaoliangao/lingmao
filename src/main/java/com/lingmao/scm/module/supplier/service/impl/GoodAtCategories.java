package com.lingmao.scm.module.supplier.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 擅长品类要查的品类（RFC 004 §3）：调 basic 的 CategoryService（架构 §3.2 第 4 条：业务判断调对方的 Service）。
 * 只认启用的，上级停用了整枝不算，和页面上的下拉一致
 */
@Component
@RequiredArgsConstructor
class GoodAtCategories {

    private final CategoryService categoryService;

    /** 启用的品类，平铺（导入时按名称对） */
    List<TreeNode> enabled() {
        List<TreeNode> flat = new ArrayList<>();
        flatten(categoryService.enabledTree(), flat);
        return flat;
    }

    /** 保存时新选的擅长品类：有停用、删掉的 → 1000 */
    void checkEnabled(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> enabled = enabled().stream().map(TreeNode::getId).collect(Collectors.toSet());
        if (!enabled.containsAll(ids)) {
            throw new BizException("擅长品类里有已经停用或者删掉的，请重新选");
        }
    }

    private static void flatten(List<TreeNode> nodes, List<TreeNode> into) {
        for (TreeNode node : nodes) {
            into.add(node);
            flatten(node.getChildren(), into);
        }
    }
}
