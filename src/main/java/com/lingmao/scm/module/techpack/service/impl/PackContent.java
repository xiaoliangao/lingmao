package com.lingmao.scm.module.techpack.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.module.techpack.entity.BomItem;
import com.lingmao.scm.module.techpack.entity.BomItemSize;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import com.lingmao.scm.module.techpack.entity.CostItem;
import com.lingmao.scm.module.techpack.mapper.BomItemMapper;
import com.lingmao.scm.module.techpack.mapper.BomItemSizeMapper;
import com.lingmao.scm.module.techpack.mapper.BomItemSkuMapper;
import com.lingmao.scm.module.techpack.mapper.CostItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 一个版本的明细（物料清单、核价的其他费用）：读出来、整版写进去、整版删掉。保存草稿 = 删了重写；修订 = 读旧版、写到新版下面
 * （架构 §4.9）。行号按列表顺序重排，从 1 起；写的时候 id 一律重新生成
 */
@Component
@RequiredArgsConstructor
class PackContent {

    private final BomItemMapper bomItemMapper;
    private final BomItemSkuMapper bomItemSkuMapper;
    private final BomItemSizeMapper bomItemSizeMapper;
    private final CostItemMapper costItemMapper;

    record BomRow(BomItem item, List<BomItemSku> skus, List<BomItemSize> sizes) {
    }

    /** 一个版本的全部明细；工艺单文件不在这里（biz_attachment，TechPackFiles） */
    record Content(List<BomRow> bom, List<CostItem> costItems) {
    }

    Content load(long packId) {
        return new Content(loadBom(packId), loadCostItems(packId));
    }

    List<BomRow> loadBom(long packId) {
        return loadBoms(List.of(packId)).getOrDefault(packId, List.of());
    }

    /** 跨单据批量检查时一次取完，查询数不随 SKU/版次数增加。 */
    Map<Long, List<BomRow>> loadBoms(Collection<Long> packIds) {
        if (packIds.isEmpty()) return Map.of();
        List<BomItem> items = bomItemMapper.selectList(new LambdaQueryWrapper<BomItem>()
                .in(BomItem::getTechPackId, packIds).orderByAsc(BomItem::getTechPackId, BomItem::getSeq, BomItem::getId));
        if (items.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = items.stream().map(BomItem::getId).toList();
        Map<Long, List<BomItemSku>> skus = group(bomItemSkuMapper.selectList(new LambdaQueryWrapper<BomItemSku>()
                .in(BomItemSku::getBomItemId, ids).orderByAsc(BomItemSku::getId)), BomItemSku::getBomItemId);
        Map<Long, List<BomItemSize>> sizes = group(bomItemSizeMapper.selectList(new LambdaQueryWrapper<BomItemSize>()
                .in(BomItemSize::getBomItemId, ids).orderByAsc(BomItemSize::getId)), BomItemSize::getBomItemId);
        return items.stream().map(i -> new BomRow(i, skus.getOrDefault(i.getId(), List.of()),
                sizes.getOrDefault(i.getId(), List.of()))).collect(Collectors.groupingBy(r -> r.item().getTechPackId()));
    }

    List<CostItem> loadCostItems(long packId) {
        return costItemMapper.selectList(new LambdaQueryWrapper<CostItem>()
                .eq(CostItem::getTechPackId, packId).orderByAsc(CostItem::getSeq, CostItem::getId));
    }

    Map<Long, List<CostItem>> loadCostItems(Collection<Long> packIds) {
        if (packIds.isEmpty()) return Map.of();
        return group(costItemMapper.selectList(new LambdaQueryWrapper<CostItem>()
                .in(CostItem::getTechPackId, packIds)
                .orderByAsc(CostItem::getTechPackId, CostItem::getSeq, CostItem::getId)), CostItem::getTechPackId);
    }

    /**
     * 整版删掉。withCost = false 时其他费用不动（没有看成本权限的人保存，不碰核价，docs/api/01 §3.5）。
     * 先删孙表再删子表：外键是 RESTRICT
     */
    void clear(long packId, boolean withCost) {
        bomItemSkuMapper.deleteByPack(packId);
        bomItemSizeMapper.deleteByPack(packId);
        bomItemMapper.delete(new LambdaQueryWrapper<BomItem>().eq(BomItem::getTechPackId, packId));
        if (withCost) {
            costItemMapper.delete(new LambdaQueryWrapper<CostItem>().eq(CostItem::getTechPackId, packId));
        }
    }

    /**
     * 写到 packId 下面：行号按顺序从 1 起，id 重新生成（传进来的对象会被改：id、techPackId、seq、上级 id）。
     * 有子表的行一行一行插（要拿到新 id 给子表），子表、没有子表的整批插。withCost = false 时不写其他费用
     */
    void write(long packId, Content content, boolean withCost) {
        int seq = 1;
        List<BomItemSku> skus = new ArrayList<>();
        List<BomItemSize> sizes = new ArrayList<>();
        for (BomRow row : content.bom()) {
            BomItem item = row.item();
            item.setId(null);
            item.setTechPackId(packId);
            item.setSeq(seq++);
            bomItemMapper.insert(item);
            row.skus().forEach(s -> {
                s.setId(null);
                s.setBomItemId(item.getId());
                skus.add(s);
            });
            row.sizes().forEach(s -> {
                s.setId(null);
                s.setBomItemId(item.getId());
                sizes.add(s);
            });
        }
        insertAll(bomItemSkuMapper::insert, skus);
        insertAll(bomItemSizeMapper::insert, sizes);

        if (withCost) {
            seq = 1;
            for (CostItem item : content.costItems()) {
                item.setId(null);
                item.setTechPackId(packId);
                item.setSeq(seq++);
            }
            insertAll(costItemMapper::insert, content.costItems());
        }
    }

    private static <T> void insertAll(Function<List<T>, ?> insert, List<T> rows) {
        if (!rows.isEmpty()) {
            insert.apply(rows);
        }
    }

    private static <T> Map<Long, List<T>> group(List<T> rows, Function<T, Long> key) {
        return rows.stream().collect(Collectors.groupingBy(key));
    }
}
