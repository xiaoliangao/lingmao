package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.module.basic.dto.MaterialCategorySaveReq;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.entity.MaterialCategory;
import com.lingmao.scm.module.basic.mapper.MaterialCategoryMapper;
import com.lingmao.scm.module.basic.service.MaterialCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MaterialCategoryServiceImpl implements MaterialCategoryService {

    private final MaterialCategoryMapper materialCategoryMapper;

    @Override
    public List<TreeNode> tree() {
        return TreeNode.build(materialCategoryMapper.selectList(null).stream().map(MaterialCategoryServiceImpl::node).toList());
    }

    @Override
    public List<TreeNode> enabledTree() {
        return TreeNode.build(materialCategoryMapper.selectList(new LambdaQueryWrapper<MaterialCategory>()
                        .eq(MaterialCategory::getStatus, Statuses.ENABLED))
                .stream().map(MaterialCategoryServiceImpl::node).toList());
    }

    @Override
    public MaterialCategory getById(long id) {
        MaterialCategory category = materialCategoryMapper.selectById(id);
        if (category == null) {
            throw new BizException(404, "物料分类不存在: " + id);
        }
        return category;
    }

    @Override
    public void requireExists(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        if (ids.stream().anyMatch(Objects::isNull)) throw new BizException(400, "物料分类不能为空");
        List<Long> wanted = ids.stream().distinct().toList();
        Set<Long> found = materialCategoryMapper.selectList(new LambdaQueryWrapper<MaterialCategory>()
                        .select(MaterialCategory::getId).in(MaterialCategory::getId, wanted)).stream()
                .map(MaterialCategory::getId).collect(Collectors.toSet());
        for (Long id : wanted) {
            if (!found.contains(id)) throw new BizException(404, "物料分类不存在: " + id);
        }
    }

    @Override
    @Transactional
    public Long create(MaterialCategorySaveReq req) {
        MaterialCategory category = new MaterialCategory();
        String name = Texts.trim(req.getName());
        if (req.getParentId() == null) {
            category.setLevel(1);   // 一级只有名称，编码不收
        } else {
            MaterialCategory parent = materialCategoryMapper.selectById(req.getParentId());
            if (parent == null) {
                throw new BizException("上级分类不存在，可能刚被删了，请刷新");
            }
            if (parent.getLevel() != 1) {
                throw new BizException("物料分类只有两级，二级下面不能再加");
            }
            String code = Texts.upper(req.getCode());
            if (code == null) {
                throw new BizException(400, "二级分类要填 2 位字母的编码：它是物料编码的前缀");
            }
            if (materialCategoryMapper.exists(new LambdaQueryWrapper<MaterialCategory>().eq(MaterialCategory::getCode, code))) {
                throw new BizException("分类编码 " + code + " 已经有了");
            }
            category.setParentId(parent.getId());
            category.setLevel(2);
            category.setCode(code);
        }
        ensureNameUnique(category.getParentId(), name, null);
        category.setName(name);
        category.setSort(req.getSort());
        materialCategoryMapper.insert(category);
        return category.getId();
    }

    @Override
    @Transactional
    public void update(long id, MaterialCategorySaveReq req) {
        MaterialCategory category = getById(id);
        if (!Objects.equals(category.getParentId(), req.getParentId())) {
            throw new BizException("上级建好就不能改：要挪到别的上级下面，在那里新建一个，再把这个停用或删掉");
        }
        if (category.getLevel() == 2 && !Objects.equals(category.getCode(), Texts.upper(req.getCode()))) {
            throw new BizException("分类编码建好就不能改：它是物料编码的前缀。录错了而且没用过的，删掉重建");
        }
        String name = Texts.trim(req.getName());
        ensureNameUnique(category.getParentId(), name, id);
        category.setName(name);
        category.setSort(req.getSort());
        materialCategoryMapper.update(category, Updates.replaceById(category));
    }

    @Override
    @Transactional
    public void delete(long id) {
        getById(id);
        long children = materialCategoryMapper.selectCount(new LambdaQueryWrapper<MaterialCategory>()
                .eq(MaterialCategory::getParentId, id));
        if (children > 0) {
            throw new BizException("下面还有 " + children + " 个二级分类，先删掉它们");
        }
        materialCategoryMapper.deleteById(id);   // 下面有物料，外键拦住 → 1005
    }

    @Override
    @Transactional
    public void enable(long id) {
        Statuses.set(materialCategoryMapper, MaterialCategory::new, id, Statuses.ENABLED, "物料分类");
    }

    @Override
    @Transactional
    public void disable(long id) {
        Statuses.set(materialCategoryMapper, MaterialCategory::new, id, Statuses.DISABLED, "物料分类");
    }

    private void ensureNameUnique(Long parentId, String name, Long excludeId) {
        boolean taken = materialCategoryMapper.exists(new LambdaQueryWrapper<MaterialCategory>()
                .eq(parentId != null, MaterialCategory::getParentId, parentId)
                .isNull(parentId == null, MaterialCategory::getParentId)
                .eq(MaterialCategory::getName, name)
                .ne(excludeId != null, MaterialCategory::getId, excludeId));
        if (taken) {
            throw new BizException("同一个上级下已经有「" + name + "」了");
        }
    }

    private static TreeNode node(MaterialCategory c) {
        return new TreeNode(c.getId(), c.getParentId(), c.getCode(), c.getName(), c.getLevel(), c.getSort(), c.getStatus());
    }
}
