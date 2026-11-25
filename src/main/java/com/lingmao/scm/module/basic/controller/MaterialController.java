package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import tools.jackson.databind.JsonNode;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.basic.dto.MaterialCreateReq;
import com.lingmao.scm.module.basic.dto.MaterialDetailVO;
import com.lingmao.scm.module.basic.dto.MaterialDupVO;
import com.lingmao.scm.module.basic.dto.MaterialListVO;
import com.lingmao.scm.module.basic.dto.MaterialPickQuery;
import com.lingmao.scm.module.basic.dto.MaterialPickVO;
import com.lingmao.scm.module.basic.dto.MaterialQuery;
import com.lingmao.scm.module.basic.dto.MaterialSaveReq;
import com.lingmao.scm.module.basic.dto.MaterialSkuSaveReq;
import com.lingmao.scm.module.basic.dto.MaterialSkuVO;
import com.lingmao.scm.module.basic.dto.MaterialTypeReq;
import com.lingmao.scm.module.basic.dto.SkuBatchReq;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.system.dto.DictOption;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Tag(name = "物料")
@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
public class MaterialController extends IdempotencyReplayGuard {

    private final MaterialService materialService;
    private final Clock clock;

    @Operation(summary = "分页：分类（含下级）、物料类型、状态；关键字查编码（空格隔开查多个）、名称、货号、成分")
    @GetMapping
    @PreAuthorize("hasAuthority('basic:material:list')")
    public R<PageResult<MaterialListVO>> page(@Valid MaterialQuery query) {
        return R.ok(materialService.page(query));
    }

    @Operation(summary = "新建物料的编码预览：按适用于这个物料类型的编码规则，只看不取（RFC 018）")
    @GetMapping("/code-preview")
    @PreAuthorize("hasAuthority('basic:material:add')")
    public R<String> codePreview(@Parameter(description = "物料分类 id，要是最末一级") @RequestParam("categoryId") Long categoryId,
                                 @Parameter(description = "物料类型（字典 material_type 的值）：按它找编码规则") @RequestParam("materialType") String materialType) {
        return R.ok(materialService.codePreview(categoryId, materialType));
    }

    @Operation(summary = "建档查重：同分类同名、同默认供应商同货号，只提示不拦")
    @GetMapping("/dup-check")
    @PreAuthorize("hasAnyAuthority('basic:material:add', 'basic:material:edit')")
    public R<List<MaterialDupVO>> dupCheck(@RequestParam(name = "categoryId", required = false) Long categoryId,
                                           @Parameter(description = "物料名称：同分类下同名的算重复") @RequestParam(name = "name", required = false) String name,
                                           @RequestParam(name = "defaultSupplierId", required = false) Long defaultSupplierId,
                                           @Parameter(description = "供应商货号：同默认供应商下同货号的算重复") @RequestParam(name = "supplierItemNo", required = false) String supplierItemNo,
                                           @RequestParam(name = "excludeId", required = false) Long excludeId) {
        return R.ok(materialService.duplicates(categoryId, name, defaultSupplierId, supplierItemNo, excludeId));
    }

    @Operation(summary = "选物料 SKU：启用物料的启用 SKU，分页（登录即可）")
    @GetMapping("/pick")
    public R<PageResult<MaterialPickVO>> pick(@Valid MaterialPickQuery query) {
        return R.ok(materialService.pick(query));
    }

    @Operation(summary = "导入模板：领猫导出的样子，物料信息 + 物料信息SKU 两张表")
    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('basic:material:import')")
    public ResponseEntity<byte[]> importTemplate() {
        return Excel.download("物料导入模板.xlsx", materialService.importTemplate());
    }

    @Operation(summary = "导入：只新增，有一处不对整批不导（1003）")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('basic:material:import')")
    @OperLog(module = "物料", action = "导入")
    public R<ImportResult> importExcel(@RequestParam("file") MultipartFile file) {
        return R.ok(materialService.importExcel(file));
    }

    @Operation(summary = "导出：和列表一样的条件，ids 是导出选中的")
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('basic:material:export')")
    public ResponseEntity<byte[]> export(@Valid MaterialQuery query) {
        return Excel.download(Excel.exportFileName("物料", LocalDate.now(clock)), materialService.export(query));
    }

    @Operation(summary = "详情：物料 + 全部 SKU + 图片 + 领猫附加属性")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:material:list')")
    public R<MaterialDetailVO> get(@PathVariable("id") Long id) {
        return R.ok(materialService.detail(id));
    }

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id}/logs")
    @PreAuthorize("hasAuthority('basic:material:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") Long id) {
        return R.ok(materialService.logs(id));
    }

    @Operation(summary = "新建物料和第一批 SKU，返回物料 id")
    @PostMapping
    @PreAuthorize("hasAuthority('basic:material:add')")
    @OperLog(module = "物料", action = "新增")
    @IdempotentCreate("MATERIAL_CREATE")
    public R<Long> create(@Valid @RequestBody MaterialCreateReq req) {
        return R.ok(materialService.create(req));
    }

    @Operation(summary = "当场加一个物料类型：新建、编辑物料时下拉里找不到用（PRD §6.1.5 规则 6），返回新的选项")
    @PostMapping("/types")
    @PreAuthorize("hasAnyAuthority('basic:material:add', 'basic:material:edit')")
    @OperLog(module = "物料", action = "新增物料类型")
    public R<DictOption> addType(@Valid @RequestBody MaterialTypeReq req) {
        return R.ok(materialService.addType(req));
    }

    @Operation(summary = "修改物料自己的列（分类、库存单位不能改）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:material:edit')")
    @OperLog(module = "物料", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody MaterialSaveReq req) {
        materialService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除（连同 SKU、图片；被引用的只能停用）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:material:remove')")
    @OperLog(module = "物料", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        materialService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启用")
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('basic:material:edit')")
    @OperLog(module = "物料", action = "启用")
    public R<Void> enable(@PathVariable("id") Long id) {
        materialService.enable(id);
        return R.ok();
    }

    @Operation(summary = "停用")
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('basic:material:edit')")
    @OperLog(module = "物料", action = "停用")
    public R<Void> disable(@PathVariable("id") Long id) {
        materialService.disable(id);
        return R.ok();
    }

    @Operation(summary = "物料图整组替换，第一张是主图")
    @PutMapping("/{id}/images")
    @PreAuthorize("hasAuthority('basic:material:edit')")
    @OperLog(module = "物料", action = "换图片")
    public R<List<AttachmentVO>> replaceImages(@PathVariable("id") Long id, @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(materialService.replaceImages(id, files));
    }

    @Operation(summary = "批量加 SKU：颜色 × 规格，已有的跳过，返回新建的")
    @PostMapping("/{id}/skus")
    @PreAuthorize("hasAuthority('basic:material:edit')")
    @OperLog(module = "物料", action = "加 SKU")
    @IdempotentCreate("MATERIAL_SKUS_CREATE")
    public R<List<MaterialSkuVO>> addSkus(@PathVariable("id") Long id, @Valid @RequestBody SkuBatchReq req) {
        return R.ok(materialService.addSkus(id, req));
    }

    @Operation(summary = "修改 SKU：色号、货号、单价、最小库存、备注")
    @PutMapping("/{id}/skus/{skuId}")
    @PreAuthorize("hasAuthority('basic:material:edit')")
    @OperLog(module = "物料", action = "改 SKU")
    public R<Void> updateSku(@PathVariable("id") Long id, @PathVariable("skuId") Long skuId,
                             @Valid @RequestBody MaterialSkuSaveReq req) {
        materialService.updateSku(id, skuId, req);
        return R.ok();
    }

    @Operation(summary = "删除 SKU（至少留一个；被引用的只能停用）")
    @DeleteMapping("/{id}/skus/{skuId}")
    @PreAuthorize("hasAuthority('basic:material:remove')")
    @OperLog(module = "物料", action = "删 SKU")
    public R<Void> deleteSku(@PathVariable("id") Long id, @PathVariable("skuId") Long skuId) {
        materialService.deleteSku(id, skuId);
        return R.ok();
    }

    @Operation(summary = "启用 SKU")
    @PostMapping("/{id}/skus/{skuId}/enable")
    @PreAuthorize("hasAuthority('basic:material:edit')")
    @OperLog(module = "物料", action = "启用 SKU")
    public R<Void> enableSku(@PathVariable("id") Long id, @PathVariable("skuId") Long skuId) {
        materialService.enableSku(id, skuId);
        return R.ok();
    }

    @Operation(summary = "停用 SKU")
    @PostMapping("/{id}/skus/{skuId}/disable")
    @PreAuthorize("hasAuthority('basic:material:edit')")
    @OperLog(module = "物料", action = "停用 SKU")
    public R<Void> disableSku(@PathVariable("id") Long id, @PathVariable("skuId") Long skuId) {
        materialService.disableSku(id, skuId);
        return R.ok();
    }

    @Operation(summary = "SKU 图整组替换")
    @PutMapping("/{id}/skus/{skuId}/images")
    @PreAuthorize("hasAuthority('basic:material:edit')")
    @OperLog(module = "物料", action = "换 SKU 图片")
    public R<List<AttachmentVO>> replaceSkuImages(@PathVariable("id") Long id, @PathVariable("skuId") Long skuId,
                                                  @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(materialService.replaceSkuImages(id, skuId, files));
    }
    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        if ("MATERIAL_CREATE".equals(operation)) materialService.detail(result.longValue());
        else materialService.detail(((Number) arguments[0]).longValue());
    }
}
