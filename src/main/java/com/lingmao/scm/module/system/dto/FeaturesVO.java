package com.lingmao.scm.module.system.dto;

/**
 * 登录信息里的功能开关（/api/auth/me 的 features）：前端只用来决定入口显示不显示，真正的拦截在后端。
 * ai：AI 功能统一开关（RFC 017 §3.7）。改了以后，别人刷新页面或重新登录才看到
 */
public record FeaturesVO(boolean ai) {
}
