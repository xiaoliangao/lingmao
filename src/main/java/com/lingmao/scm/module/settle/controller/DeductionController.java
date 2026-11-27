package com.lingmao.scm.module.settle.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.settle.dto.DeductionQuery;
import com.lingmao.scm.module.settle.dto.DeductionReq;
import com.lingmao.scm.module.settle.dto.DeductionRowVO;
import com.lingmao.scm.module.settle.service.DeductionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 扣款登记（RFC 019 §5.2，docs/api/09 §3）：平时登记，月底生成对账单时按日期拉进去 */
@Tag(name = "扣款登记")
@RestController
@RequestMapping("/api/deductions")
@RequiredArgsConstructor
public class DeductionController {

    private final DeductionService deductionService;

    @Operation(summary = "列表，日期新的在前；带进了哪张没作废的对账单")
    @GetMapping
    @PreAuthorize("hasAuthority('settle:deduction:list')")
    public R<PageResult<DeductionRowVO>> page(@Valid DeductionQuery query) {
        return R.ok(deductionService.page(query));
    }

    @Operation(summary = "登记一条扣款，返回 id")
    @PostMapping
    @PreAuthorize("hasAuthority('settle:deduction:edit')")
    @OperLog(module = "扣款登记", action = "登记")
    public R<Long> create(@Valid @RequestBody DeductionReq req) {
        return R.ok(deductionService.create(req));
    }

    @Operation(summary = "改（要带 version）：进了没作废的对账单的不能改（1000）；返回新的 version")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('settle:deduction:edit')")
    @OperLog(module = "扣款登记", action = "改")
    public R<Integer> update(@PathVariable("id") long id, @Valid @RequestBody DeductionReq req) {
        return R.ok(deductionService.update(id, req));
    }

    @Operation(summary = "删：进了没作废的对账单的不能删（1000）")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('settle:deduction:edit')")
    @OperLog(module = "扣款登记", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        deductionService.delete(id);
        return R.ok();
    }
}
