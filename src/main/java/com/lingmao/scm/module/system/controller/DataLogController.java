package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.service.DataLogService;
import com.lingmao.scm.module.system.dto.DataLogView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class DataLogController {
    private final DataLogService service;
    public DataLogController(DataLogService service) { this.service = service; }
    @GetMapping("/api/data-logs")
    @PreAuthorize("hasAuthority('system:audit:query') and hasAuthority('data:all')")
    public R<List<DataLogView>> list(@RequestParam(required=false) String table, @RequestParam(required=false) Long rowId,
            @RequestParam(required=false) String rootTable, @RequestParam(required=false) Long rootId,
            @RequestParam(required=false) Long transactionId, @RequestParam(required=false) Long beforeId,
            @RequestParam(defaultValue="50") int limit) {
        return R.ok(service.list(table,rowId,rootTable,rootId,transactionId,beforeId,limit));
    }
}
