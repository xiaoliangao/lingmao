package com.lingmao.scm.module.inventory.dto;

public record StockLogExportPlan(StockLogExportReq filters, long watermark, boolean financialColumns, long maxRows) {}
