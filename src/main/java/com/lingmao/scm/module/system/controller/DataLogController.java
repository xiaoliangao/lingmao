package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.service.DataLogService;
import com.lingmao.scm.module.system.dto.DataLogView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 数据改动记录：数据库触发器记下的每一行改了什么（只给有审计权限、数据范围是全部的人） */
@Tag(name = "数据改动记录")
@RestController
public class DataLogController {
    private final DataLogService service;
    public DataLogController(DataLogService service) { this.service = service; }
    @Operation(summary = "查数据改动记录：按表和行，或按单据（根表和根 id）查，新的在前；翻页带上一页最后一条的 id")
    @GetMapping("/api/data-logs")
    @PreAuthorize("hasAuthority('system:audit:query') and hasAuthority('data:all')")
    public R<List<DataLogView>> list(
            @Parameter(description = "表名，如 pur_order_item；和 rowId 一起用") @RequestParam(required=false) String table,
            @Parameter(description = "那张表里的行 id") @RequestParam(required=false) Long rowId,
            @Parameter(description = "单据的主表名，如 tp_tech_pack：查这张单连同明细的改动；和 rootId 一起用") @RequestParam(required=false) String rootTable,
            @Parameter(description = "单据 id") @RequestParam(required=false) Long rootId,
            @Parameter(description = "只看某一次保存（数据库事务 id）") @RequestParam(required=false) Long transactionId,
            @Parameter(description = "翻页：上一页最后一条的 id，取比它早的") @RequestParam(required=false) Long beforeId,
            @Parameter(description = "最多几条，默认 50") @RequestParam(defaultValue="50") int limit) {
        return R.ok(service.list(table,rowId,rootTable,rootId,transactionId,beforeId,limit));
    }
}
