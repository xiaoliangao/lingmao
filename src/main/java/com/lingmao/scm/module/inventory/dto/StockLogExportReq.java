package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.common.page.BusinessDateRange;
import com.lingmao.scm.module.inventory.entity.StockChangeType;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 导出条件特意不带分页、游标、所属人和服务端控制的字段。 */
@Data
public class StockLogExportReq {
    @Positive(message = "仓库编号必须为正整数") private Long warehouseId;
    @Positive(message = "物料 SKU 编号必须为正整数") private Long materialSkuId;
    @Size(max = 30, message = "一次最多选择 30 种变动类型") private List<StockChangeType> changeType;
    private LocalDate bizDateFrom;
    private LocalDate bizDateTo;
    @Size(max = 120, message = "筛选关键词最长 120 个字") private String keyword;
    @Size(max = 100, message = "一次最多选择 100 个流水编号")
    private List<@jakarta.validation.constraints.NotNull(message = "流水编号不能为空") @Positive(message = "流水编号必须为正整数") Long> ids;
    private LocalDate createdDateFrom;
    private LocalDate createdDateTo;

    public StockLogQuery query() {
        BusinessDateRange.validate(bizDateFrom, bizDateTo); BusinessDateRange.validate(createdDateFrom, createdDateTo);
        var q = new StockLogQuery(); q.setWarehouseId(warehouseId); q.setMaterialSkuId(materialSkuId); q.setChangeType(changeType);
        q.setBizDateFrom(bizDateFrom); q.setBizDateTo(bizDateTo); q.setKeyword(keyword); q.setIds(ids);
        q.setCreatedDateFrom(createdDateFrom); q.setCreatedDateTo(createdDateTo); return q;
    }
    public static StockLogExportReq from(StockLogQuery q) {
        var req = new StockLogExportReq(); req.setWarehouseId(q.getWarehouseId()); req.setMaterialSkuId(q.getMaterialSkuId());
        req.setChangeType(q.getChangeType() == null ? null : List.copyOf(q.getChangeType())); req.setBizDateFrom(q.getBizDateFrom());
        req.setBizDateTo(q.getBizDateTo()); req.setKeyword(q.getKeyword()); req.setIds(q.getIds() == null ? null : List.copyOf(q.getIds()));
        req.setCreatedDateFrom(q.getCreatedDateFrom()); req.setCreatedDateTo(q.getCreatedDateTo()); return req;
    }
}
