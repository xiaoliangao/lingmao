package com.lingmao.scm.module.techpack.controller;

import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import tools.jackson.databind.JsonNode;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.excel.ExcelPreview;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import com.lingmao.scm.module.techpack.dto.BomDefaultsReq;
import com.lingmao.scm.module.techpack.dto.BomImportVO;
import com.lingmao.scm.module.techpack.dto.PackFileVO;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;
import com.lingmao.scm.module.techpack.dto.TechPackCreateReq;
import com.lingmao.scm.module.techpack.dto.TechPackDetailVO;
import com.lingmao.scm.module.techpack.dto.TechPackOverviewVO;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import com.lingmao.scm.module.techpack.dto.VersionLockReq;
import com.lingmao.scm.module.techpack.service.TechPackCostService;
import com.lingmao.scm.module.techpack.service.TechPackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 资料包（docs/api/01 §3） */
@Tag(name = "资料包")
@RestController
@RequestMapping("/api/tech-packs")
@RequiredArgsConstructor
public class TechPackController extends IdempotencyReplayGuard {

    private final TechPackService techPackService;
    private final TechPackCostService costService;

    @Operation(summary = "一个款的版本列表和概况（默认选哪一版、能建哪个阶段、单件成本）")
    @GetMapping
    @PreAuthorize("hasAuthority('techpack:pack:list')")
    public R<TechPackOverviewVO> overview(@Parameter(description = "款式 id") @RequestParam("styleId") long styleId) {
        return R.ok(techPackService.overview(styleId));
    }

    @Operation(summary = "一个版本：单头、物料清单、工艺单文件")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('techpack:pack:list')")
    public R<TechPackDetailVO> detail(@PathVariable("id") long id) {
        return R.ok(techPackService.detail(id));
    }

    @Operation(summary = "建下一版草稿，返回 id（已有草稿、款式停用 1000）")
    @PostMapping
    @PreAuthorize("hasAuthority('techpack:pack:add')")
    @OperLog(module = "资料包", action = "新建")
    @IdempotentCreate("TECHPACK_CREATE")
    public R<Long> create(@Valid @RequestBody TechPackCreateReq req) {
        return R.ok(techPackService.create(req.getStyleId()));
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

    @Operation(summary = "生效（检查没通过 1000，data 是问题清单；旧的已生效版作废）")
    @PostMapping("/{id:\\d+}/publish")
    @PreAuthorize("hasAuthority('techpack:pack:publish')")
    @OperLog(module = "资料包", action = "生效")
    public R<Void> publish(@PathVariable("id") long id, @Valid @RequestBody VersionLockReq req) {
        techPackService.publish(id, req.getVersionLock());
        return R.ok();
    }

    @Operation(summary = "修订：已生效版复制成下一版草稿，返回新 id")
    @PostMapping("/{id:\\d+}/revise")
    @PreAuthorize("hasAuthority('techpack:pack:add')")
    @OperLog(module = "资料包", action = "修订")
    @IdempotentCreate("TECHPACK_REVISE")
    public R<Long> revise(@PathVariable("id") long id) {
        return R.ok(techPackService.revise(id));
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

    @Operation(summary = "从甲方的 BOM 表认出物料清单（只解析、不写库；只有草稿能调）")
    @PostMapping(value = "/{id:\\d+}/bom-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('techpack:pack:edit')")
    public R<BomImportVO> bomImport(@PathVariable("id") long id, @RequestParam("file") MultipartFile file,
                                    @Parameter(description = "用第几张 sheet（从 0 起），不给 = 按款号、款名自动对") @RequestParam(value = "sheet", required = false) Integer sheet,
                                    @Parameter(description = "人工挑的物料：Excel 行号:物料 id，几个用逗号分开，如 7:12,9:15") @RequestParam(value = "picks", required = false) String picks) {
        if (file == null || file.isEmpty()) {
            throw new BizException("文件不能为空");
        }
        try (InputStream in = file.getInputStream()) {
            return R.ok(techPackService.bomImport(id, in, sheet, picks(picks)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传的文件失败", e);
        }
    }

    @Operation(summary = "一个版本的工艺单文件（生产单详情上发工厂用）")
    @GetMapping("/{id:\\d+}/files")
    @PreAuthorize("hasAuthority('techpack:pack:list')")
    public R<List<PackFileVO>> files(@PathVariable("id") long id) {
        return R.ok(techPackService.files(id));
    }

    @Operation(summary = "工艺单页签上一个 Excel 的表格数据（预览用；不是 Excel 1000）")
    @GetMapping("/{id:\\d+}/files/{fileId:\\d+}/preview")
    @PreAuthorize("hasAuthority('techpack:pack:list')")
    public R<ExcelPreview.Book> preview(@PathVariable("id") long id, @PathVariable("fileId") long fileId) {
        return R.ok(techPackService.preview(id, fileId));
    }

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('techpack:pack:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(techPackService.logs(id));
    }
    /** "7:12,9:15" → {7=12, 9=15}；格式不对 400 */
    static Map<Integer, Long> picks(String text) {
        Map<Integer, Long> out = new HashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String pair : text.split(",")) {
            String[] kv = pair.strip().split(":");
            try {
                out.put(Integer.parseInt(kv[0].strip()), Long.parseLong(kv[1].strip()));
            } catch (RuntimeException e) {
                throw new BizException(400, "picks 的格式是 行号:物料id，几个用逗号分开");
            }
        }
        return out;
    }

    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        if (!"TECHPACK_CREATE".equals(operation)) techPackService.detail(((Number) arguments[0]).longValue());
        techPackService.detail(result.longValue());
    }
}
