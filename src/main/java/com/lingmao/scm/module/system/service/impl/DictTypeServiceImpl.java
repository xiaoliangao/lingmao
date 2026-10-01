package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.DictTypeQuery;
import com.lingmao.scm.module.system.dto.DictTypeSaveReq;
import com.lingmao.scm.module.system.entity.DictType;
import com.lingmao.scm.module.system.mapper.DictTypeMapper;
import com.lingmao.scm.module.system.service.DictTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Service 实现 = 业务规则住的地方：唯一性检查、状态流转、多表一起写时的事务，都在这一层。
 * 原则：Controller 不写业务，Mapper 不写业务，业务只在 Service。
 * 构造器注入：字段 final + @RequiredArgsConstructor 生成构造器，Spring 自动把 Mapper 塞进来。
 */
@Service
@RequiredArgsConstructor
public class DictTypeServiceImpl implements DictTypeService {

    private final DictTypeMapper dictTypeMapper;

    @Override
    public PageResult<DictType> page(DictTypeQuery query) {
        // LambdaQueryWrapper：用方法引用代替列名字符串，列名拼错在编译期就能发现
        // 第一个参数是"条件是否生效"：前端没传 code 就不加这个 like
        LambdaQueryWrapper<DictType> wrapper = new LambdaQueryWrapper<DictType>()
                .like(StringUtils.hasText(query.getCode()), DictType::getCode, query.getCode())
                .like(StringUtils.hasText(query.getName()), DictType::getName, query.getName())
                .orderByDesc(DictType::getId);
        return PageResult.of(dictTypeMapper.selectPage(query.toPage(), wrapper));
    }

    @Override
    public DictType getById(Long id) {
        DictType entity = dictTypeMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "字典类型不存在: " + id);
        }
        return entity;
    }

    @Override
    @Transactional
    public Long create(DictTypeSaveReq req) {
        ensureCodeUnique(req.getCode(), null);
        DictType entity = new DictType();
        BeanUtils.copyProperties(req, entity);   // 同名字段从 DTO 拷到实体
        dictTypeMapper.insert(entity);           // 插入后 entity.id 会被回填
        return entity.getId();
    }

    @Override
    @Transactional
    public void update(Long id, DictTypeSaveReq req) {
        DictType entity = getById(id);           // 不存在直接抛 404
        ensureCodeUnique(req.getCode(), id);     // 排除自己再查重
        BeanUtils.copyProperties(req, entity);
        dictTypeMapper.updateById(entity);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getById(id);
        dictTypeMapper.deleteById(id);           // 有 @TableLogic，实际执行的是 UPDATE ... SET deleted = true
    }

    /** 业务规则：编码在未删除的记录里唯一。修改时要排除自己。 */
    private void ensureCodeUnique(String code, Long excludeId) {
        Long count = dictTypeMapper.selectCount(new LambdaQueryWrapper<DictType>()
                .eq(DictType::getCode, code)
                .ne(excludeId != null, DictType::getId, excludeId));
        if (count > 0) {
            throw new BizException("编码已存在: " + code);
        }
    }
}
