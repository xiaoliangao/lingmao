package com.lingmao.scm.module.production.controller;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.production.dto.CapacityPreviewVO;
import com.lingmao.scm.module.production.dto.CapacityQuery;
import com.lingmao.scm.module.production.dto.CapacityRowVO;
import com.lingmao.scm.module.production.dto.CapacitySaveReq;
import com.lingmao.scm.module.production.service.FactoryCapacityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 工厂产能（PRD 08.02，甲方 11-27 答 Q60）：理单、生产跟单每月登记，排单时排满了拦住 */
@Tag(name = "工厂产能")
@RestController
@RequestMapping("/api/factory-capacities")
@RequiredArgsConstructor
public class FactoryCapacityController {

    private final FactoryCapacityService factoryCapacityService;

    @Operation(summary = "某个月各家厂、各品类用的产能，和这个月交期的单占了多少")
    @GetMapping
    @PreAuthorize("hasAuthority('production:capacity:list')")
    public R<List<CapacityRowVO>> list(@Valid CapacityQuery query) {
        return R.ok(factoryCapacityService.list(query));
    }

    @Operation(summary = "登记产能：同一家厂、同一个品类、同一个月已经有就覆盖")
    @PostMapping
    @PreAuthorize("hasAuthority('production:capacity:edit')")
    public R<Long> save(@Valid @RequestBody CapacitySaveReq req) {
        return R.ok(factoryCapacityService.save(req));
    }

    @Operation(summary = "删一条登记：删了按更早登记的算，都没有就不再查")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('production:capacity:edit')")
    public R<Void> delete(@PathVariable("id") long id) {
        factoryCapacityService.delete(id);
        return R.ok();
    }

    @Operation(summary = "排单时看：这家厂做这个款、交期这天还能排多少件（没登记产能是空数组）")
    @GetMapping("/preview")
    @PreAuthorize("hasAnyAuthority('production:order:add', 'production:order:edit')")
    public R<List<CapacityPreviewVO>> preview(@RequestParam("factoryId") long factoryId, @RequestParam("styleId") long styleId,
                                              @RequestParam("dueDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                                              @RequestParam(value = "excludeId", required = false) Long excludeId) {
        return R.ok(factoryCapacityService.preview(factoryId, styleId, dueDate, excludeId));
    }
}
