package com.lingmao.scm.module.style.service;

import com.lingmao.scm.module.style.dto.StyleMatrixExportRows.ColorAxis;
import com.lingmao.scm.module.style.dto.StyleMatrixExportRows.SizeAxis;
import com.lingmao.scm.module.style.dto.StyleMatrixExportRows.SkuCell;
import com.lingmao.scm.module.style.mapper.StyleExportProjectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 导出专用跨域只读入口；停用的颜色和 SKU 保留，调用方负责累计总预算。 */
@Service
@RequiredArgsConstructor
public class StyleExportProjection {
    private final StyleExportProjectionMapper mapper;
    public List<SizeAxis> sizes(List<Long> ids, long offset) { check(ids, offset); return ids.isEmpty() ? List.of() : mapper.sizes(ids, offset); }
    public List<ColorAxis> colors(List<Long> ids, long offset) { check(ids, offset); return ids.isEmpty() ? List.of() : mapper.colors(ids, offset); }
    public List<SkuCell> skus(List<Long> ids, long offset) { check(ids, offset); return ids.isEmpty() ? List.of() : mapper.skus(ids, offset); }
    public List<SkuCell> cells(List<Long> ids, long offset) { check(ids, offset); return ids.isEmpty() ? List.of() : mapper.cells(ids, offset); }
    private static void check(List<Long> ids, long offset) {
        if (ids == null || ids.size() > 500 || ids.stream().anyMatch(id -> id == null || id <= 0)
                || offset < 0 || offset > 50_000)
            throw new IllegalArgumentException("导出投影只接受最多 500 个有效编号及有界偏移");
    }
}
