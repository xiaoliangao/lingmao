package com.lingmao.scm.module.basic.service;

import com.lingmao.scm.module.basic.dto.MaterialCategorySaveReq;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.entity.MaterialCategory;

import java.util.List;
import java.util.Collection;

/**
 * 物料分类：两级（RFC 003 §5.4）。一级只有名称，二级必须有 2 位字母编码（物料编码前缀）；
 * 编码、上级建好不改；同一上级下不重名；有下级的不能删
 */
public interface MaterialCategoryService {

    /** 整棵树，停用的也在（基础页、物料页左边的树） */
    List<TreeNode> tree();

    /** 只有启用的（下拉） */
    List<TreeNode> enabledTree();

    MaterialCategory getById(long id);

    /** 批量校验引用存在；不改变分类状态或获取写锁。 */
    void requireExists(Collection<Long> ids);

    Long create(MaterialCategorySaveReq req);

    void update(long id, MaterialCategorySaveReq req);

    void delete(long id);

    void enable(long id);

    void disable(long id);
}
