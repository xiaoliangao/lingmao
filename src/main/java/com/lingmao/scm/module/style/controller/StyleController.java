package com.lingmao.scm.module.style.controller;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.style.dto.StyleColorsReq;
import com.lingmao.scm.module.style.dto.StyleCreateReq;
import com.lingmao.scm.module.style.dto.StyleCreateResult;
import com.lingmao.scm.module.style.dto.StyleDetailVO;
import com.lingmao.scm.module.style.dto.StyleListVO;
import com.lingmao.scm.module.style.dto.StylePickQuery;
import com.lingmao.scm.module.style.dto.StylePickVO;
import com.lingmao.scm.module.style.dto.StyleQuery;
import com.lingmao.scm.module.style.dto.StyleSaveReq;
import com.lingmao.scm.module.style.dto.StyleSkuSaveReq;
import com.lingmao.scm.module.style.dto.StyleSkuVO;
import com.lingmao.scm.module.style.service.StyleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

/** 款式（RFC 005 §7）。权限码 style:style:*，菜单在 开发 › 款式（V16） */
@Tag(name = "款式")
@RestController
@RequestMapping("/api/styles")
@RequiredArgsConstructor
public class StyleController {

    private final StyleService styleService;
    private final Clock clock;

    // ---------- 列表、选款式、导入导出 ----------

    @Operation(summary = "分页：品类（含下级）、大类、品牌、年份、季节、波段、设计师、状态、资料包状态；关键字查款号（空格隔开查多个）、名称、花名、品名")
    @GetMapping
    @PreAuthorize("hasAuthority('style:style:list')")
    public R<PageResult<StyleListVO>> page(@Valid StyleQuery query) {
        return R.ok(styleService.page(query));
    }

    @Operation(summary = "自动编号预览：只看不取，保存时才真取号（RFC 005 §3.1）")
    @GetMapping("/code-preview")
    @PreAuthorize("hasAuthority('style:style:add')")
    public R<String> codePreview(@RequestParam("styleClass") String styleClass,
                                 @RequestParam(name = "designerId", required = false) Long designerId) {
        return R.ok(styleService.codePreview(styleClass, designerId));
    }

    @Operation(summary = "选款式：启用的款式，分页（登录即可）")
    @GetMapping("/pick")
    public R<PageResult<StylePickVO>> pick(@Valid StylePickQuery query) {
        return R.ok(styleService.pick(query));
    }

    @Operation(summary = "导入模板：一张表一行一款，颜色逗号隔开")
    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('style:style:import')")
    public ResponseEntity<byte[]> importTemplate() {
        return Excel.download("款式导入模板.xlsx", styleService.importTemplate());
    }

    @Operation(summary = "导入：只新增，有一处不对整批不导（1003）")
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('style:style:import')")
    @OperLog(module = "款式", action = "导入")
    public R<ImportResult> importExcel(@RequestParam("file") MultipartFile file) {
        return R.ok(styleService.importExcel(file));
    }

    @Operation(summary = "导出：和列表一样的条件，ids 是导出选中的；款式一张表、SKU 一张表")
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('style:style:export')")
    public ResponseEntity<byte[]> export(@Valid StyleQuery query) {
        return Excel.download(Excel.exportFileName("款式", LocalDate.now(clock)), styleService.export(query));
    }

    // ---------- 款式 ----------

    @Operation(summary = "详情：款式 + 颜色 + 尺码组 + 图片 + 设计附件 + 领猫附加属性 + 能做的动作")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('style:style:list')")
    public R<StyleDetailVO> get(@PathVariable("id") Long id) {
        return R.ok(styleService.detail(id));
    }

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('style:style:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") Long id) {
        return R.ok(styleService.logs(id));
    }

    @Operation(summary = "新建（草稿）：款号自动编号或手填；带了颜色的同时生成 SKU。返回实际的款号")
    @PostMapping
    @PreAuthorize("hasAuthority('style:style:add')")
    @OperLog(module = "款式", action = "新增")
    public R<StyleCreateResult> create(@Valid @RequestBody StyleCreateReq req) {
        return R.ok(styleService.create(req));
    }

    @Operation(summary = "修改款式自己的列（款号、大类不能改；有 SKU 后尺码组不能换）")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('style:style:edit')")
    @OperLog(module = "款式", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody StyleSaveReq req) {
        styleService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除（连同 SKU、颜色、图片；被订单、资料包、价格档案引用的只能停用）")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('style:style:remove')")
    @OperLog(module = "款式", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        styleService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启用：要有启用的 SKU")
    @PostMapping("/{id:\\d+}/enable")
    @PreAuthorize("hasAuthority('style:style:edit')")
    @OperLog(module = "款式", action = "启用")
    public R<Void> enable(@PathVariable("id") Long id) {
        styleService.enable(id);
        return R.ok();
    }

    @Operation(summary = "停用")
    @PostMapping("/{id:\\d+}/disable")
    @PreAuthorize("hasAuthority('style:style:edit')")
    @OperLog(module = "款式", action = "停用")
    public R<Void> disable(@PathVariable("id") Long id) {
        styleService.disable(id);
        return R.ok();
    }

    @Operation(summary = "款式图整组替换，第一张是主图")
    @PutMapping("/{id:\\d+}/images")
    @PreAuthorize("hasAuthority('style:style:edit')")
    public R<List<AttachmentVO>> replaceImages(@PathVariable("id") Long id, @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(styleService.replaceImages(id, files));
    }

    @Operation(summary = "设计附件整组替换")
    @PutMapping("/{id:\\d+}/files")
    @PreAuthorize("hasAuthority('style:style:edit')")
    public R<List<AttachmentVO>> replaceFiles(@PathVariable("id") Long id, @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(styleService.replaceFiles(id, files));
    }

    // ---------- 颜色 ----------

    @Operation(summary = "加颜色：追加在后面，要启用；已经有的报错")
    @PostMapping("/{id:\\d+}/colors")
    @PreAuthorize("hasAuthority('style:style:edit')")
    @OperLog(module = "款式", action = "加颜色")
    public R<Void> addColors(@PathVariable("id") Long id, @Valid @RequestBody StyleColorsReq req) {
        styleService.addColors(id, req.getColorIds());
        return R.ok();
    }

    @Operation(summary = "排颜色：款式上全部颜色的新顺序")
    @PutMapping("/{id:\\d+}/colors/order")
    @PreAuthorize("hasAuthority('style:style:edit')")
    public R<Void> reorderColors(@PathVariable("id") Long id, @Valid @RequestBody StyleColorsReq req) {
        styleService.reorderColors(id, req.getColorIds());
        return R.ok();
    }

    @Operation(summary = "启用颜色")
    @PostMapping("/{id:\\d+}/colors/{colorId:\\d+}/enable")
    @PreAuthorize("hasAuthority('style:style:edit')")
    public R<Void> enableColor(@PathVariable("id") Long id, @PathVariable("colorId") Long colorId) {
        styleService.enableColor(id, colorId);
        return R.ok();
    }

    @Operation(summary = "停用颜色（不连带停用它的 SKU）")
    @PostMapping("/{id:\\d+}/colors/{colorId:\\d+}/disable")
    @PreAuthorize("hasAuthority('style:style:edit')")
    public R<Void> disableColor(@PathVariable("id") Long id, @PathVariable("colorId") Long colorId) {
        styleService.disableColor(id, colorId);
        return R.ok();
    }

    @Operation(summary = "去掉颜色：生成过 SKU 的只能停用")
    @DeleteMapping("/{id:\\d+}/colors/{colorId:\\d+}")
    @PreAuthorize("hasAuthority('style:style:edit')")
    @OperLog(module = "款式", action = "去掉颜色")
    public R<Void> removeColor(@PathVariable("id") Long id, @PathVariable("colorId") Long colorId) {
        styleService.removeColor(id, colorId);
        return R.ok();
    }

    // ---------- SKU ----------

    @Operation(summary = "SKU，按颜色顺序、尺码顺序")
    @GetMapping("/{id:\\d+}/skus")
    @PreAuthorize("hasAuthority('style:style:list')")
    public R<List<StyleSkuVO>> skus(@PathVariable("id") Long id) {
        return R.ok(styleService.skus(id));
    }

    @Operation(summary = "一键生成：启用的颜色 × 尺码组的尺码，已有的跳过；返回新建了几个")
    @PostMapping("/{id:\\d+}/skus/generate")
    @PreAuthorize("hasAuthority('style:style:edit')")
    @OperLog(module = "款式", action = "生成 SKU")
    public R<Integer> generateSkus(@PathVariable("id") Long id) {
        return R.ok(styleService.generateSkus(id));
    }

    @Operation(summary = "改 SKU 的条码、吊牌价")
    @PutMapping("/{id:\\d+}/skus/{skuId:\\d+}")
    @PreAuthorize("hasAuthority('style:style:edit')")
    public R<Void> updateSku(@PathVariable("id") Long id, @PathVariable("skuId") Long skuId,
                             @Valid @RequestBody StyleSkuSaveReq req) {
        styleService.updateSku(id, skuId, req);
        return R.ok();
    }

    @Operation(summary = "删 SKU（下过订单的只能停用）")
    @DeleteMapping("/{id:\\d+}/skus/{skuId:\\d+}")
    @PreAuthorize("hasAuthority('style:style:remove')")
    @OperLog(module = "款式", action = "删除 SKU")
    public R<Void> deleteSku(@PathVariable("id") Long id, @PathVariable("skuId") Long skuId) {
        styleService.deleteSku(id, skuId);
        return R.ok();
    }

    @Operation(summary = "启用 SKU")
    @PostMapping("/{id:\\d+}/skus/{skuId:\\d+}/enable")
    @PreAuthorize("hasAuthority('style:style:edit')")
    public R<Void> enableSku(@PathVariable("id") Long id, @PathVariable("skuId") Long skuId) {
        styleService.enableSku(id, skuId);
        return R.ok();
    }

    @Operation(summary = "停用 SKU")
    @PostMapping("/{id:\\d+}/skus/{skuId:\\d+}/disable")
    @PreAuthorize("hasAuthority('style:style:edit')")
    public R<Void> disableSku(@PathVariable("id") Long id, @PathVariable("skuId") Long skuId) {
        styleService.disableSku(id, skuId);
        return R.ok();
    }
}
