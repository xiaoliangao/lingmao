package com.lingmao.scm.module.techpack.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.module.techpack.entity.BomItem;
import com.lingmao.scm.module.techpack.entity.BomItemSize;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import com.lingmao.scm.module.techpack.entity.CostItem;
import com.lingmao.scm.module.techpack.entity.CraftText;
import com.lingmao.scm.module.techpack.entity.PackProcess;
import com.lingmao.scm.module.techpack.entity.ProcessImage;
import com.lingmao.scm.module.techpack.entity.SpecPoint;
import com.lingmao.scm.module.techpack.entity.SpecValue;
import com.lingmao.scm.module.techpack.mapper.BomItemMapper;
import com.lingmao.scm.module.techpack.mapper.BomItemSizeMapper;
import com.lingmao.scm.module.techpack.mapper.BomItemSkuMapper;
import com.lingmao.scm.module.techpack.mapper.CostItemMapper;
import com.lingmao.scm.module.techpack.mapper.CraftTextMapper;
import com.lingmao.scm.module.techpack.mapper.PackProcessMapper;
import com.lingmao.scm.module.techpack.mapper.ProcessImageMapper;
import com.lingmao.scm.module.techpack.mapper.SpecPointMapper;
import com.lingmao.scm.module.techpack.mapper.SpecValueMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 一个版本的明细（四个页签）：读出来、整版写进去、整版删掉。保存草稿 = 删了重写；修订、转生产 BOM = 读旧版、写到新版下面
 * （架构 §4.9）。行号按列表顺序重排，从 1 起；写的时候 id 一律重新生成
 */
@Component
@RequiredArgsConstructor
class PackContent {

    private final SpecPointMapper specPointMapper;
    private final SpecValueMapper specValueMapper;
    private final BomItemMapper bomItemMapper;
    private final BomItemSkuMapper bomItemSkuMapper;
    private final BomItemSizeMapper bomItemSizeMapper;
    private final CraftTextMapper craftTextMapper;
    private final PackProcessMapper processMapper;
    private final ProcessImageMapper processImageMapper;
    private final CostItemMapper costItemMapper;

    record SpecRow(SpecPoint point, List<SpecValue> values) {
    }

    record BomRow(BomItem item, List<BomItemSku> skus, List<BomItemSize> sizes) {
    }

    record ProcessRow(PackProcess process, List<ProcessImage> images) {
    }

    /** 一个版本的全部明细；工艺指示的插图不在这里（biz_attachment，跟着 AttachmentService 走） */
    record Content(List<SpecRow> specs, List<BomRow> bom, List<CraftText> texts, List<ProcessRow> processes,
                   List<CostItem> costItems) {
    }

    Content load(long packId) {
        return new Content(loadSpecs(packId), loadBom(packId), loadTexts(packId), loadProcesses(packId), loadCostItems(packId));
    }

    List<BomRow> loadBom(long packId) {
        List<BomItem> items = bomItemMapper.selectList(new LambdaQueryWrapper<BomItem>()
                .eq(BomItem::getTechPackId, packId).orderByAsc(BomItem::getSeq, BomItem::getId));
        if (items.isEmpty()) {
            return List.of();
        }
        List<Long> ids = items.stream().map(BomItem::getId).toList();
        Map<Long, List<BomItemSku>> skus = group(bomItemSkuMapper.selectList(new LambdaQueryWrapper<BomItemSku>()
                .in(BomItemSku::getBomItemId, ids).orderByAsc(BomItemSku::getId)), BomItemSku::getBomItemId);
        Map<Long, List<BomItemSize>> sizes = group(bomItemSizeMapper.selectList(new LambdaQueryWrapper<BomItemSize>()
                .in(BomItemSize::getBomItemId, ids).orderByAsc(BomItemSize::getId)), BomItemSize::getBomItemId);
        return items.stream().map(i -> new BomRow(i, skus.getOrDefault(i.getId(), List.of()),
                sizes.getOrDefault(i.getId(), List.of()))).toList();
    }

    List<ProcessRow> loadProcesses(long packId) {
        List<PackProcess> processes = processMapper.selectList(new LambdaQueryWrapper<PackProcess>()
                .eq(PackProcess::getTechPackId, packId).orderByAsc(PackProcess::getSeq, PackProcess::getId));
        if (processes.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ProcessImage>> images = group(processImageMapper.selectList(new LambdaQueryWrapper<ProcessImage>()
                        .in(ProcessImage::getProcessId, processes.stream().map(PackProcess::getId).toList())
                        .orderByAsc(ProcessImage::getSort, ProcessImage::getId)), ProcessImage::getProcessId);
        return processes.stream().map(p -> new ProcessRow(p, images.getOrDefault(p.getId(), List.of()))).toList();
    }

    List<CostItem> loadCostItems(long packId) {
        return costItemMapper.selectList(new LambdaQueryWrapper<CostItem>()
                .eq(CostItem::getTechPackId, packId).orderByAsc(CostItem::getSeq, CostItem::getId));
    }

    List<CraftText> loadTexts(long packId) {
        return craftTextMapper.selectList(new LambdaQueryWrapper<CraftText>().eq(CraftText::getTechPackId, packId));
    }

    private List<SpecRow> loadSpecs(long packId) {
        List<SpecPoint> points = specPointMapper.selectList(new LambdaQueryWrapper<SpecPoint>()
                .eq(SpecPoint::getTechPackId, packId).orderByAsc(SpecPoint::getSeq, SpecPoint::getId));
        if (points.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SpecValue>> values = group(specValueMapper.selectList(new LambdaQueryWrapper<SpecValue>()
                .in(SpecValue::getSpecPointId, points.stream().map(SpecPoint::getId).toList())
                .orderByAsc(SpecValue::getId)), SpecValue::getSpecPointId);
        return points.stream().map(p -> new SpecRow(p, values.getOrDefault(p.getId(), List.of()))).toList();
    }

    /**
     * 整版删掉。withCost = false 时其他费用不动（没有看成本权限的人保存，不碰核价，docs/api/01 §3.5）。
     * 先删孙表再删子表：外键是 RESTRICT
     */
    void clear(long packId, boolean withCost) {
        specValueMapper.deleteByPack(packId);
        specPointMapper.delete(new LambdaQueryWrapper<SpecPoint>().eq(SpecPoint::getTechPackId, packId));
        bomItemSkuMapper.deleteByPack(packId);
        bomItemSizeMapper.deleteByPack(packId);
        bomItemMapper.delete(new LambdaQueryWrapper<BomItem>().eq(BomItem::getTechPackId, packId));
        craftTextMapper.delete(new LambdaQueryWrapper<CraftText>().eq(CraftText::getTechPackId, packId));
        processImageMapper.deleteByPack(packId);
        processMapper.delete(new LambdaQueryWrapper<PackProcess>().eq(PackProcess::getTechPackId, packId));
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
        List<SpecValue> values = new ArrayList<>();
        for (SpecRow row : content.specs()) {
            SpecPoint p = row.point();
            p.setId(null);
            p.setTechPackId(packId);
            p.setSeq(seq++);
            specPointMapper.insert(p);
            row.values().forEach(v -> {
                v.setId(null);
                v.setSpecPointId(p.getId());
                values.add(v);
            });
        }
        insertAll(specValueMapper::insert, values);

        seq = 1;
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

        List<CraftText> texts = content.texts().stream().filter(t -> t.getContent() != null && !t.getContent().isBlank())
                .peek(t -> {
                    t.setId(null);
                    t.setTechPackId(packId);
                }).toList();
        insertAll(craftTextMapper::insert, texts);

        seq = 1;
        List<ProcessImage> images = new ArrayList<>();
        for (ProcessRow row : content.processes()) {
            PackProcess p = row.process();
            p.setId(null);
            p.setTechPackId(packId);
            p.setSeq(seq++);
            processMapper.insert(p);
            int sort = 0;
            for (ProcessImage image : row.images()) {
                image.setId(null);
                image.setProcessId(p.getId());
                image.setSort(sort++);
                images.add(image);
            }
        }
        insertAll(processImageMapper::insert, images);

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
