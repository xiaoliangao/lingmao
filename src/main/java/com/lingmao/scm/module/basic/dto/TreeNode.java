package com.lingmao.scm.module.basic.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 品类、物料分类的树节点（el-tree、el-tree-select 直接能用）。品类没有编码，code 是 null、不出现在 JSON 里。
 * children 没有下级时是空数组
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TreeNode {

    private Long id;
    private Long parentId;
    private String code;
    private String name;
    private Integer level;
    private Integer sort;
    private Integer status;
    private List<TreeNode> children = new ArrayList<>();

    public TreeNode(Long id, Long parentId, String code, String name, Integer level, Integer sort, Integer status) {
        this.id = id;
        this.parentId = parentId;
        this.code = code;
        this.name = name;
        this.level = level;
        this.sort = sort;
        this.status = status;
    }

    /**
     * 平铺的节点连成树，同级按 sort、id 排。上级不在 nodes 里的（比如只取启用的，上级停用了）连同它的下级一起丢掉：
     * 上级停用了，下拉里整枝都不出现
     */
    public static List<TreeNode> build(Collection<TreeNode> nodes) {
        Comparator<TreeNode> order = Comparator.comparing(TreeNode::getSort, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TreeNode::getId);
        Map<Long, TreeNode> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.getId(), n));
        List<TreeNode> roots = new ArrayList<>();
        nodes.stream().sorted(order).forEach(n -> {
            if (n.getParentId() == null) {
                roots.add(n);
            } else {
                TreeNode parent = byId.get(n.getParentId());
                if (parent != null) {
                    parent.getChildren().add(n);
                }
            }
        });
        return roots;
    }
}
