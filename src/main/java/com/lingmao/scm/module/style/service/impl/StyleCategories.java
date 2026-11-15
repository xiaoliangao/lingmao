package com.lingmao.scm.module.style.service.impl;

import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 品类树在款式这边的用法：筛选"含下级"、详情和导出的全路径"女装/上衣/衬衫"、导入按全路径找品类。
 * 品类就几十个，每次整棵取（CategoryService.tree，停用的也在）在内存里算，不为它写 SQL
 */
@Component
@RequiredArgsConstructor
class StyleCategories {

    /** 全路径的分隔：导出写 /，导入也认 > */
    static final String SEPARATOR = "/";

    private final CategoryService categoryService;

    Index index() {
        return new Index(categoryService.tree());
    }

    /** id 连同它下面所有级的 id；id 是 null 返回 null（不筛） */
    List<Long> withDescendants(Long id) {
        return id == null ? null : index().withDescendants(id);
    }

    /** 整棵树摊平以后的查法 */
    static final class Index {

        private final Map<Long, TreeNode> byId = new HashMap<>();
        private final Map<String, TreeNode> byPath = new HashMap<>();

        Index(List<TreeNode> roots) {
            Deque<TreeNode> stack = new ArrayDeque<>(roots);
            while (!stack.isEmpty()) {
                TreeNode node = stack.pop();
                byId.put(node.getId(), node);
                stack.addAll(node.getChildren());
            }
            byId.values().forEach(node -> byPath.putIfAbsent(path(node.getId()), node));
        }

        String name(Long id) {
            TreeNode node = id == null ? null : byId.get(id);
            return node == null ? null : node.getName();
        }

        /** 女装/上衣/衬衫；不在树里返回 null */
        String path(Long id) {
            List<String> names = new ArrayList<>();
            TreeNode node = id == null ? null : byId.get(id);
            while (node != null) {
                names.addFirst(node.getName());
                node = node.getParentId() == null ? null : byId.get(node.getParentId());
            }
            return names.isEmpty() ? null : String.join(SEPARATOR, names);
        }

        /** 按全路径找，各级名称两边的空白不算，/ 和 > 都认；找不到返回 null */
        TreeNode byPath(String path) {
            if (path == null) {
                return null;
            }
            String normalized = String.join(SEPARATOR, List.of(path.strip().split("\\s*[/>]\\s*")));
            return byPath.get(normalized);
        }

        List<Long> withDescendants(long id) {
            List<Long> ids = new ArrayList<>();
            TreeNode root = byId.get(id);
            if (root == null) {
                ids.add(id);   // 不在树里（刚被删了）：照样按它筛，查出来是空的
                return ids;
            }
            Deque<TreeNode> stack = new ArrayDeque<>(List.of(root));
            while (!stack.isEmpty()) {
                TreeNode node = stack.pop();
                ids.add(node.getId());
                node.getChildren().stream().filter(Objects::nonNull).forEach(stack::push);
            }
            return ids;
        }
    }
}
