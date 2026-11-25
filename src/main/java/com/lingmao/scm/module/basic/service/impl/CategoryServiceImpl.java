package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.module.basic.dto.CategorySaveReq;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.entity.Category;
import com.lingmao.scm.module.basic.mapper.CategoryMapper;
import com.lingmao.scm.module.basic.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    /** 最多几级（PRD §6.1.2） */
    static final int MAX_LEVEL = 3;

    private final CategoryMapper categoryMapper;

    @Override
    public List<TreeNode> tree() {
        return TreeNode.build(categoryMapper.selectList(null).stream().map(CategoryServiceImpl::node).toList());
    }

    @Override
    public List<TreeNode> enabledTree() {
        return TreeNode.build(categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                        .eq(Category::getStatus, Statuses.ENABLED))
                .stream().map(CategoryServiceImpl::node).toList());
    }

    @Override
    public Category getById(long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BizException(404, "品类不存在: " + id);
        }
        return category;
    }

    @Override
    @Transactional
    public Long create(CategorySaveReq req) {
        int level = 1;
        if (req.getParentId() != null) {
            Category parent = categoryMapper.selectById(req.getParentId());
            if (parent == null) {
                throw new BizException("上级品类不存在，可能刚被删了，请刷新");
            }
            level = parent.getLevel() + 1;
        }
        if (level > MAX_LEVEL) {
            throw new BizException("品类最多 " + MAX_LEVEL + " 级，第 3 级下面不能再加");
        }
        String name = Texts.trim(req.getName());
        ensureNameUnique(req.getParentId(), name, null);
        Category category = new Category();
        category.setParentId(req.getParentId());
        category.setLevel(level);
        category.setName(name);
        category.setCode(code(req.getCode()));
        category.setSort(req.getSort());
        categoryMapper.insert(category);
        return category.getId();
    }

    @Override
    @Transactional
    public void update(long id, CategorySaveReq req) {
        Category category = getById(id);
        if (!Objects.equals(category.getParentId(), req.getParentId())) {
            throw new BizException("上级建好就不能改：要挪到别的上级下面，在那里新建一个，再把这个停用或删掉");
        }
        String name = Texts.trim(req.getName());
        ensureNameUnique(category.getParentId(), name, id);
        category.setName(name);
        category.setCode(code(req.getCode()));
        category.setSort(req.getSort());
        categoryMapper.update(category, Updates.replaceById(category));
    }

    @Override
    @Transactional
    public void delete(long id) {
        getById(id);
        long children = categoryMapper.selectCount(new LambdaQueryWrapper<Category>().eq(Category::getParentId, id));
        if (children > 0) {
            throw new BizException("下面还有 " + children + " 个子品类，先删掉它们");
        }
        categoryMapper.deleteById(id);   // 被款式、供应商的擅长品类引用了，外键拦住 → 1005
    }

    @Override
    @Transactional
    public void enable(long id) {
        Statuses.set(categoryMapper, Category::new, id, Statuses.ENABLED, "品类");
    }

    @Override
    @Transactional
    public void disable(long id) {
        Statuses.set(categoryMapper, Category::new, id, Statuses.DISABLED, "品类");
    }

    @Override
    public void checkSelectable(long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BizException("品类不存在，可能刚被删了，请刷新");
        }
        // 自己和各级上级都要启用：上级停用了，下拉里整枝都不出现
        for (Category c = category; c != null; c = c.getParentId() == null ? null : categoryMapper.selectById(c.getParentId())) {
            if (!Statuses.enabled(c.getStatus())) {
                throw new BizException("品类「" + c.getName() + "」已经停用了，请换一个");
            }
        }
        boolean hasChildren = categoryMapper.exists(new LambdaQueryWrapper<Category>()
                .eq(Category::getParentId, id)
                .eq(Category::getStatus, Statuses.ENABLED));
        if (hasChildren) {
            throw new BizException("品类「" + category.getName() + "」下面还有子品类，请选到最末一级");
        }
    }

    /** 同一上级下不重名（顶层之间也不重名）。excludeId 是修改时的自己 */
    private void ensureNameUnique(Long parentId, String name, Long excludeId) {
        boolean taken = categoryMapper.exists(new LambdaQueryWrapper<Category>()
                .eq(parentId != null, Category::getParentId, parentId)
                .isNull(parentId == null, Category::getParentId)
                .eq(Category::getName, name)
                .ne(excludeId != null, Category::getId, excludeId));
        if (taken) {
            throw new BizException("同一个上级下已经有「" + name + "」了");
        }
    }

    private static TreeNode node(Category c) {
        return new TreeNode(c.getId(), c.getParentId(), c.getCode(), c.getName(), c.getLevel(), c.getSort(), c.getStatus());
    }

    /** 品类编码：空的存 null，字母转大写 */
    private static String code(String text) {
        return text == null || text.isBlank() ? null : text.strip().toUpperCase(java.util.Locale.ROOT);
    }
}
