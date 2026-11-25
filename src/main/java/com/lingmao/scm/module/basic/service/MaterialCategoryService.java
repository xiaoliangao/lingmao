package com.lingmao.scm.module.basic.service;

import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.module.basic.dto.MaterialCategorySaveReq;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.entity.MaterialCategory;

import org.springframework.web.multipart.MultipartFile;

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

    /** 导入模板：领猫"基础档案"类目导出的列（RFC 018 §8.7） */
    byte[] importTemplate();

    /** 导入物料分类：按路径对，已有的改编码、状态，没有的建上；有一行不对整批不导（RFC 018 §8.7） */
    ImportResult importExcel(MultipartFile file);
}
