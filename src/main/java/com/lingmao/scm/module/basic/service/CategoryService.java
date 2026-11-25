package com.lingmao.scm.module.basic.service;

import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.module.basic.dto.CategorySaveReq;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.entity.Category;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 款式品类：三级树（RFC 003 §5.1）。层级由上级算，最多 3 级；上级建好不改；同一上级下不重名；有下级的不能删
 */
public interface CategoryService {

    /** 整棵树，停用的也在（基础页） */
    List<TreeNode> tree();

    /** 只有启用的（下拉）：上级停用了，整枝都不出现 */
    List<TreeNode> enabledTree();

    Category getById(long id);

    Long create(CategorySaveReq req);

    void update(long id, CategorySaveReq req);

    void delete(long id);

    void enable(long id);

    void disable(long id);

    /**
     * 款式新建、换品类时调（RFC 003 §5.1）：能不能选这个品类——存在、它和各级上级都启用、下面没有启用的子品类（叶子）。
     * 不能选报 1000，说清为什么
     */
    void checkSelectable(long id);

    /** 导入模板：领猫"基础档案"类目导出的列（RFC 018 §8.7） */
    byte[] importTemplate();

    /** 导入品类：按路径对，已有的改编码、状态，没有的建上；有一行不对整批不导（RFC 018 §8.7） */
    ImportResult importExcel(MultipartFile file);
}
