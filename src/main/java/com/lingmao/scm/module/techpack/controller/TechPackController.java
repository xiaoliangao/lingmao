package com.lingmao.scm.module.techpack.controller;

import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import tools.jackson.databind.JsonNode;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import com.lingmao.scm.module.techpack.dto.BomDefaultsReq;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;
import com.lingmao.scm.module.techpack.dto.TechPackCreateReq;
import com.lingmao.scm.module.techpack.dto.TechPackDetailVO;
import com.lingmao.scm.module.techpack.dto.TechPackOverviewVO;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import com.lingmao.scm.module.techpack.dto.VersionLockReq;
import com.lingmao.scm.module.techpack.service.TechPackCostService;
import com.lingmao.scm.module.techpack.service.TechPackPdfService;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 资料包（docs/api/01 §3） */
@Tag(name = "资料包")
@RestController
@RequestMapping("/api/tech-packs")
@RequiredArgsConstructor
public class TechPackController extends IdempotencyReplayGuard {

    private final TechPackService techPackService;
    private final TechPackCostService costService;
    private final TechPackPdfService pdfService;

    @Operation(summary = "一个款的版本列表和概况（默认选哪一版、能建哪个阶段、单件成本）")
    @GetMapping
    @PreAuthorize("hasAuthority('techpack:pack:list')")
    public R<TechPackOverviewVO> overview(@Parameter(description = "款式 id") @RequestParam("styleId") long styleId) {
        return R.ok(techPackService.overview(styleId));
    }

    @Operation(summary = "一个版本：单头、尺寸指示、物料清单、工艺指示")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('techpack:pack:list')")
    public R<TechPackDetailVO> detail(@PathVariable("id") long id) {
        return R.ok(techPackService.detail(id));
    }

    @Operation(summary = "建一个阶段的下一版草稿，返回 id（这个阶段已有草稿、款式停用 1000）")
    @PostMapping
    @PreAuthorize("hasAuthority('techpack:pack:add')")
    @OperLog(module = "资料包", action = "新建")
    @IdempotentCreate("TECHPACK_CREATE")
    public R<Long> create(@Valid @RequestBody TechPackCreateReq req) {
        return R.ok(techPackService.create(req.getStyleId(), req.getStage()));
    }

    @Operation(summary = "保存草稿（整版覆盖），返回新的 versionLock。cost 是 null 时不动核价")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('techpack:pack:edit')")
    @OperLog(module = "资料包", action = "保存")
    public R<Integer> save(@PathVariable("id") long id, @Valid @RequestBody TechPackSaveReq req) {
        return R.ok(techPackService.save(id, req));
    }

    @Operation(summary = "删草稿（不是草稿 1002）")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('techpack:pack:remove')")
    @OperLog(module = "资料包", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        techPackService.delete(id);
        return R.ok();
    }

    @Operation(summary = "生效（检查没通过 1000，data 是问题清单；同阶段旧的已生效版作废）")
    @PostMapping("/{id:\\d+}/publish")
    @PreAuthorize("hasAuthority('techpack:pack:publish')")
    @OperLog(module = "资料包", action = "生效")
    public R<Void> publish(@PathVariable("id") long id, @Valid @RequestBody VersionLockReq req) {
        techPackService.publish(id, req.getVersionLock());
        return R.ok();
    }

    @Operation(summary = "修订：已生效版复制成同阶段的下一版草稿，返回新 id")
    @PostMapping("/{id:\\d+}/revise")
    @PreAuthorize("hasAuthority('techpack:pack:add')")
    @OperLog(module = "资料包", action = "修订")
    @IdempotentCreate("TECHPACK_REVISE")
    public R<Long> revise(@PathVariable("id") long id) {
        return R.ok(techPackService.revise(id));
    }

    @Operation(summary = "转生产 BOM：研发已生效版复制成生产阶段的下一版草稿，单价按价格档案重新取，返回新 id")
    @PostMapping("/{id:\\d+}/to-prod")
    @PreAuthorize("hasAuthority('techpack:pack:add')")
    @OperLog(module = "资料包", action = "转生产 BOM")
    @IdempotentCreate("TECHPACK_TO_PROD")
    public R<Long> toProd(@PathVariable("id") long id) {
        return R.ok(techPackService.toProd(id));
    }

    @Operation(summary = "几行 BOM 的默认值（供应商、损耗、来源、单价），按请求的顺序")
    @PostMapping("/{id:\\d+}/bom-defaults")
    @PreAuthorize("hasAuthority('techpack:pack:edit')")
    public R<List<BomDefaultVO>> bomDefaults(@PathVariable("id") long id, @Valid @RequestBody BomDefaultsReq req) {
        return R.ok(techPackService.bomDefaults(id, req.getItems()));
    }

    @Operation(summary = "核价（打开时现算；草稿算的是上次保存的）")
    @GetMapping("/{id:\\d+}/cost")
    @PreAuthorize("hasAuthority('techpack:cost:query')")
    public R<TechPackCostVO> cost(@PathVariable("id") long id,
                                  @Parameter(description = "按哪个颜色算，不给 = 款式第一个颜色") @RequestParam(value = "colorId", required = false) Long colorId) {
        return R.ok(costService.cost(id, colorId));
    }

    @Operation(summary = "导出 PDF 工艺单（含价格要看成本的权限，没有 403；草稿也能导）")
    @GetMapping("/{id:\\d+}/pdf")
    @PreAuthorize("hasAuthority('techpack:pack:list')")
    public ResponseEntity<byte[]> pdf(@PathVariable("id") long id,
                                      @Parameter(description = "只出这个颜色的物料清单，不给 = 全部颜色") @RequestParam(value = "colorId", required = false) Long colorId,
                                      @Parameter(description = "含单价、单件成本、核价和工序工价") @RequestParam(value = "price", defaultValue = "false") boolean price,
                                      @Parameter(description = "附工艺标准文件") @RequestParam(value = "appendix", defaultValue = "true") boolean appendix) {
        if (price && !LoginUser.hasAuthority("techpack:cost:query")) {
            throw new BizException(403, "导出含价格的工艺单要有看成本的权限：不勾「含价格」再导，或者找管理员开权限");
        }
        TechPackPdfService.PdfFile file = pdfService.export(id, colorId, price, appendix);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('techpack:pack:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(techPackService.logs(id));
    }
    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        if (!"TECHPACK_CREATE".equals(operation)) techPackService.detail(((Number) arguments[0]).longValue());
        techPackService.detail(result.longValue());
    }
}
