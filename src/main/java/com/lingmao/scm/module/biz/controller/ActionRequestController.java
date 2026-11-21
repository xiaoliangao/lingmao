package com.lingmao.scm.module.biz.controller;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.ActionStateVO;
import com.lingmao.scm.module.biz.service.ActionRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 提交结果核对（docs/api/README §2）：建单这类提交网络中断、不知道成没成功时，前端拿提交编号来问。
 * 登录即可，只查自己的提交，不返回单据内容
 */
@Tag(name = "提交结果")
@RestController
@RequestMapping("/api/http-actions")
@RequiredArgsConstructor
public class ActionRequestController {

    private final ActionRequestService actionRequestService;

    @Operation(summary = "这次提交成功了没有（只查自己的）")
    @GetMapping("/{key}")
    public R<ActionStateVO> state(@Parameter(description = "提交编号：提交时请求头 Idempotency-Key 里带的那个 UUID") @PathVariable UUID key) {
        return R.ok(new ActionStateVO(actionRequestService.completed(key)));
    }
}
