package com.lingmao.scm.module.system.dto;

import java.util.List;

/**
 * 管理后台 › 消息推送：钉钉配好没有、推哪几种、有几条发失败了（RFC 019 §4）。
 *
 * @param configured     部署配置里钉钉的 AppKey、AppSecret、AgentId 都有了
 * @param linkConfigured 配了系统的外网地址（SCM_PUBLIC_URL）：消息里才能带"打开"按钮
 * @param events         每种消息推不推
 * @param failedCount    发失败、不再重试的条数（重试 5 次还不行的、收件人找不到的）
 */
public record NotifySettingsVO(boolean configured, boolean linkConfigured, List<EventOption> events, long failedCount) {

    /** 一种消息：value 是 NotifyEvent 的名字 */
    public record EventOption(String value, String label, String description, boolean enabled) {
    }
}
