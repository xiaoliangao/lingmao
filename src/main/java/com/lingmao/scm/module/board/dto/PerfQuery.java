package com.lingmao.scm.module.board.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 供应商绩效（GET /api/reports/supplier-performance/…，RFC 019 §3）：按约定交期落在这段时间里的单算 */
@Data
public class PerfQuery {
    /** 约定交期从哪天（含）；物料供应商是采购行的交期（没有用单头的），加工厂是生产单的交期 */
    @NotNull(message = "请选开始日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;
    /** 到哪天（含）；次品率按这段时间里登记的回货算 */
    @NotNull(message = "请选结束日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;
    /** 查供应商的编码、简称、名称 */
    private String keyword;
}
