package com.lingmao.scm.module.techpack.controller;

import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.style.dto.StyleCreateReq;
import com.lingmao.scm.module.style.dto.StyleCreateResult;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.techpack.service.TechPackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 款式的"复制成新款"（RFC 012 §5.4）。地址挂在款式下面，放在资料包这边：要连资料包一起复制，
 * 款式不能反过来调资料包（架构 §3.2）
 */
@Tag(name = "款式")
@RestController
@RequestMapping("/api/styles")
@RequiredArgsConstructor
public class StyleCopyController extends IdempotencyReplayGuard {

    private final TechPackService techPackService;
    private final StyleService styleService;

    @Operation(summary = "复制成新款：请求和新建一样（页面从老款带过来，尺码组能换、款号重新发）；老款已生效的资料包复制成新款的草稿。返回新款")
    @PostMapping("/{id:\\d+}/copy")
    @PreAuthorize("hasAuthority('style:style:add') and hasAuthority('techpack:pack:add')")
    @OperLog(module = "款式", action = "复制成新款")
    @IdempotentCreate("STYLE_COPY")
    public R<StyleCreateResult> copy(@PathVariable("id") long id, @Valid @RequestBody StyleCreateReq req) {
        return R.ok(techPackService.copyStyle(id, req));
    }

    /** 重放（同一次点击又发了一次）：老款、新款都还看得到才给结果 */
    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        styleService.detail(((Number) arguments[0]).longValue());
        styleService.detail(result.get("id").longValue());
    }
}
