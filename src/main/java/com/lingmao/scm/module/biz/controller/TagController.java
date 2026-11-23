package com.lingmao.scm.module.biz.controller;

import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.TagArchiveReq;
import com.lingmao.scm.module.biz.dto.TagObjectVO;
import com.lingmao.scm.module.biz.dto.TagQuery;
import com.lingmao.scm.module.biz.dto.TagRenameReq;
import com.lingmao.scm.module.biz.dto.TagReplaceReq;
import com.lingmao.scm.module.biz.dto.TagVO;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.TagService;
import com.lingmao.scm.module.biz.service.TagFilterService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import java.util.List;

@RestController
@RequestMapping("/api/tags")
public class TagController extends IdempotencyReplayGuard {
    private final TagService service;
    private final TagFilterService filters;
    public TagController(TagService service, TagFilterService filters) { this.service = service; this.filters = filters; }

    @InitBinder void queryFields(WebDataBinder binder) {
        binder.setAllowedFields("keyword", "size", "includeIds", "includeIds[*]", "page");
    }

    @GetMapping
    @PreAuthorize("hasAuthority('biz:tag:list')")
    @Operation(summary = "公共标签字面前缀选项；含指定历史 ID，总量最多 100")
    public R<List<TagVO>> options(@Valid TagQuery query) { return R.ok(service.options(query)); }

    @GetMapping("/filter-options")
    @PreAuthorize("hasAuthority('biz:tag:list')")
    @Operation(summary = "列表筛选标签选项，包含归档历史标签；总量最多 100")
    public R<List<TagVO>> filterOptions(@Valid TagQuery query) { return R.ok(filters.options(query)); }

    @GetMapping("/objects/{type}/{id:\\d+}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "读取真实领域对象标签；领域权限、数据范围和主体每次现验")
    public R<TagObjectVO> read(@PathVariable("type") TagObjectType type, @PathVariable("id") long id) {
        return R.ok(service.read(type, id));
    }

    @PostMapping("/objects/{type}/{id:\\d+}/replace")
    @PreAuthorize("isAuthenticated()")
    @IdempotentCreate("TAG_REPLACE")
    @OperLog(module = "公共标签", action = "替换对象标签")
    @Operation(summary = "完整替换对象标签；必须携带对象/库版本、精确保留 ID 和幂等键")
    public R<TagObjectVO> replace(@PathVariable("type") TagObjectType type, @PathVariable("id") long id,
                                  @Valid @RequestBody TagReplaceReq request) {
        return R.ok(service.replace(type, id, request));
    }

    @PostMapping("/{id:\\d+}/rename")
    @PreAuthorize("hasAuthority('biz:tag:manage')")
    @IdempotentCreate("TAG_RENAME")
    @OperLog(module = "公共标签", action = "改名")
    public R<TagVO> rename(@PathVariable("id") long id, @Valid @RequestBody TagRenameReq request) {
        return R.ok(service.rename(id, request));
    }

    @PostMapping("/{id:\\d+}/archive")
    @PreAuthorize("hasAuthority('biz:tag:manage')")
    @IdempotentCreate("TAG_ARCHIVE")
    @OperLog(module = "公共标签", action = "归档")
    public R<TagVO> archive(@PathVariable("id") long id, @Valid @RequestBody TagArchiveReq request) {
        return R.ok(service.archive(id, request));
    }

    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('biz:tag:list')")
    public R<PageResult<DocLogVO>> logs(@PathVariable("id") long id, @Valid PageQuery query) {
        return R.ok(service.logs(id, query));
    }

    @Override public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        switch (operation) {
            case "TAG_REPLACE" -> service.authorizeReplaceReplay((TagObjectType) arguments[0], (Long) arguments[1]);
            case "TAG_RENAME", "TAG_ARCHIVE" -> service.authorizeLibraryReplay((Long) arguments[0]);
            default -> throw new IllegalStateException("unknown tag idempotency operation");
        }
    }
}
