package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.system.cache.DictCache;
import com.lingmao.scm.module.system.dto.DictDataQuery;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.dto.DictDataVO;
import com.lingmao.scm.module.system.entity.DictData;
import com.lingmao.scm.module.system.entity.DictType;
import com.lingmao.scm.module.system.mapper.DictDataMapper;
import com.lingmao.scm.module.system.mapper.DictTypeMapper;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service //告诉 Spring "这是一个要交给你管理的对象"。启动时 Spring 会创建它的唯一一个实例，谁需要 DictDataService，就把这个实例给谁。
@RequiredArgsConstructor
public class DictDataServiceImpl implements DictDataService {

    //private final + @RequiredArgsConstructor：Lombok 会给所有 final 字段生成一个构造器，Spring 通过这个构造器把两个 Mapper 传进来，这叫"构造器注入"。
    //为什么用 final：注入后就不能再被改掉；而且忘了注入，编译时就会报错，不会等到运行时才空指针。
    private final DictDataMapper dictDataMapper;
    private final DictTypeMapper dictTypeMapper;
    private final DictCache dictCache;
    private final TradeFeature tradeFeature;

    @Override
    public PageResult<DictDataVO> page(DictDataQuery query) {
        /**
         * LambdaQueryWrapper 是用 Java 代码拼 WHERE 条件的工具。
         * DictData::getTypeCode 代替字符串 "type_code" 写列名：列名拼错的话，编译时就能发现。
         * .eq(条件, 列, 值)：第一个参数是 false 时，这一句不拼。前端没传 typeCode 时，StringUtils.hasText 返回 false，就不按类型筛选。
         * .eq 和 .like：typeCode 是精确匹配（等于），label 是模糊匹配（包含），因为管理员搜"春"应该能搜出"春季"。
         * 排序：先按 sort，sort 一样再按 id。只按 sort 的话，sort 相同的几行每次查出来的顺序可能不一样，翻页时会出现重复或漏行。
         */
        LambdaQueryWrapper<DictData> wrapper = new LambdaQueryWrapper<DictData>()
                .eq(StringUtils.hasText(query.getTypeCode()), DictData::getTypeCode, query.getTypeCode())
                .like(StringUtils.hasText(query.getLabel()), DictData::getLabel, query.getLabel())
                .orderByAsc(DictData::getSort)
                .orderByAsc(DictData::getId);
        return PageResult.of(dictDataMapper.selectPage(query.toPage(), wrapper)
                .convert(d -> DictDataVO.of(d, tradeFeature.isReservedDict(d.getTypeCode(), d.getValue()))));
    }

    @Override
    public DictData getById(Long id) {
        DictData entity = dictDataMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "字典数据不存在: " + id);
        }
        return entity;
    }

    @Override
    public List<DictOption> listByType(String typeCode) {
        List<DictOption> cached = dictCache.get(typeCode);
        if (cached != null) {
            return tradeFeature.filterDict(typeCode, cached, DictOption::value);
        }
        // 停用的也要：旧数据里存着停用的值，显示时要能翻成文字。下拉框只列启用的，由前端按 status 过滤
        List<DictOption> options = dictDataMapper.selectList(new LambdaQueryWrapper<DictData>()
                        .eq(DictData::getTypeCode, typeCode)
                        .orderByAsc(DictData::getSort)
                        .orderByAsc(DictData::getId))
                .stream().map(d -> new DictOption(d.getLabel(), d.getValue(), d.getStatus())).toList();
        dictCache.put(typeCode, options);
        return tradeFeature.filterDict(typeCode, options, DictOption::value);
    }

    @Override
    @Transactional
    public Long create(DictDataSaveReq req) {
        ensureTypeExists(req.getTypeCode());
        ensureValueUnique(req.getTypeCode(), req.getValue(), null);
        DictData entity = new DictData();
        BeanUtils.copyProperties(req, entity);
        dictDataMapper.insert(entity);
        dictCache.evictAfterCommit(req.getTypeCode());
        return entity.getId();
    }

    @Override
    @Transactional
    public void update(Long id, DictDataSaveReq req) {
        DictData entity = getById(id);
        // value 建好不改（RFC 003 §4.2）：业务表存的是 value，改了旧数据就对不上；system 在最底下，查不了哪些表用过它。
        // 挪到别的类型同理。要换就新建一项、停用旧的
        if (!Objects.equals(entity.getTypeCode(), req.getTypeCode())) {
            throw new BizException("字典项不能挪到别的类型：新建一项，再停用这一项");
        }
        if (!Objects.equals(entity.getValue(), req.getValue())) {
            throw new BizException("存储值建好就不能改：业务数据里存的是它，改了旧数据就对不上。要换就新建一项，再停用这一项");
        }
        if (Boolean.TRUE.equals(entity.getBuiltin()) && Objects.equals(req.getStatus(), 0)) {
            throw new BizException("「" + entity.getLabel() + "」是内置项，不能停用：系统按它的值认它。名称、排序、数值能改");
        }
        BeanUtils.copyProperties(req, entity);
        dictDataMapper.update(entity, Updates.replaceById(entity));   // 整体覆盖，备注能清空（bug 005）
        dictCache.evictAfterCommit(entity.getTypeCode());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        DictData entity = getById(id);
        if (Boolean.TRUE.equals(entity.getBuiltin())) {
            throw new BizException("「" + entity.getLabel() + "」是内置项，不能删除：系统按它的值认它");
        }
        dictDataMapper.deleteById(id);
        dictCache.evictAfterCommit(entity.getTypeCode());
    }

    private void ensureTypeExists(String typeCode) {
        boolean exists = dictTypeMapper.exists(new LambdaQueryWrapper<DictType>()
                .eq(DictType::getCode, typeCode));
        if (!exists) {
            throw new BizException("字典类型不存在: " + typeCode);
        }
    }

    private void ensureValueUnique(String typeCode, String value, Long excludeId) {
        boolean exists = dictDataMapper.exists(new LambdaQueryWrapper<DictData>()
                .eq(DictData::getTypeCode, typeCode)
                .eq(DictData::getValue, value)
                .ne(excludeId != null, DictData::getId, excludeId));
        if (exists) {
            throw new BizException("存储值已存在: " + value);
        }
    }

    @Override
    public List<DictData> listWithText(String typeCode) {
        return dictDataMapper.selectList(new LambdaQueryWrapper<DictData>()
                .eq(DictData::getTypeCode, typeCode)
                .orderByAsc(DictData::getSort, DictData::getId));
    }

    @Override
    @Transactional
    public void updateTextValues(String typeCode, Map<String, String> texts) {
        texts.forEach((value, text) -> {
            int updated = dictDataMapper.update(new DictData(), new LambdaUpdateWrapper<DictData>()
                    .set(DictData::getTextValue, StringUtils.hasText(text) ? text.strip() : null)
                    .eq(DictData::getTypeCode, typeCode)
                    .eq(DictData::getValue, value));
            if (updated == 0) {
                throw new BizException("「" + value + "」不在字典里了，可能刚被删了，请刷新");
            }
        });
    }

    @Override
    @Transactional
    public void updateNumValues(String typeCode, Map<String, BigDecimal> nums) {
        nums.forEach((value, num) -> {
            int updated = dictDataMapper.update(new DictData(), new LambdaUpdateWrapper<DictData>()
                    .set(DictData::getNumValue, num)
                    .eq(DictData::getTypeCode, typeCode)
                    .eq(DictData::getValue, value));
            if (updated == 0) {
                throw new BizException("「" + value + "」不在字典里了，可能刚被删了，请刷新");
            }
        });
    }
}
