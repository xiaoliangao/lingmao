package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.NotifyEventsReq;
import com.lingmao.scm.module.system.dto.NotifyMessageQuery;
import com.lingmao.scm.module.system.dto.NotifyMessageRow;
import com.lingmao.scm.module.system.dto.NotifySettingsVO;
import com.lingmao.scm.module.system.service.NotifyService;
import com.lingmao.scm.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理后台 › 消息推送（甲方 11-27 答 Q65，RFC 019 §4）：只给超级管理员 */
@Tag(name = "消息推送")
@RestController
@RequestMapping("/api/admin/notify")
@RequiredArgsConstructor
public class NotifyController {

    private final NotifyService notifyService;

    @Operation(summary = "钉钉配好没有、推哪几种消息、有几条发失败了（只给超级管理员）")
    @GetMapping
    @PreAuthorize("@superAdmin.check(authentication)")
    public R<NotifySettingsVO> settings() {
        return R.ok(notifyService.settings());
    }

    @Operation(summary = "改推哪几种消息（只给超级管理员）：立刻生效")
    @PutMapping("/events")
    @PreAuthorize("@superAdmin.check(authentication)")
    @OperLog(module = "消息推送", action = "改推送种类")
    public R<NotifySettingsVO> setEvents(@Valid @RequestBody NotifyEventsReq req) {
        return R.ok(notifyService.setEvents(req.events()));
    }

    @Operation(summary = "最近发的钉钉消息，新的在前；failed=true 只看发失败的（只给超级管理员）")
    @GetMapping("/messages")
    @PreAuthorize("@superAdmin.check(authentication)")
    public R<PageResult<NotifyMessageRow>> messages(@Valid NotifyMessageQuery query) {
        return R.ok(notifyService.messages(query));
    }

    @Operation(summary = "发失败的消息重发（只给超级管理员）：还在发、已经发出去的报 1000")
    @PostMapping("/messages/{id}/retry")
    @PreAuthorize("@superAdmin.check(authentication)")
    @OperLog(module = "消息推送", action = "重发")
    public R<Void> retry(@PathVariable("id") Long id) {
        notifyService.retry(id);
        return R.ok();
    }

    @Operation(summary = "给自己发一条测试消息（只给超级管理员）：当场发，不通的报钉钉的原话（1000）")
    @PostMapping("/test")
    @PreAuthorize("@superAdmin.check(authentication)")
    public R<String> test(@AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(notifyService.test(loginUser.userId()));
    }
}
