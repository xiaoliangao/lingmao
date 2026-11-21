package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.AiSwitchVO;

/**
 * AI 功能统一开关（RFC 017 §3.7）：sys_config 的 ai.switch。只在这里读，别处都问它（和 TradeFeature 一样）。
 * 和贸易代工开关不同，它不在部署配置里：出了问题要马上关，不能等重启。每次都读库、不缓存，改了下一个请求就按新的走；
 * 只有登录信息和以后 AI 的请求才问它，普通请求不多查一次。
 * <p>
 * 现在只有总开关。以后每一档功能（助手、识别预填、企查查 agent、后台归因）做的时候加自己的分开关，
 * 也在这里读；助手令牌、agent 账号的拦截随那一档一起做（RFC 017 §3.7"在 app 里拦"）
 */
public interface AiSwitchService {

    /** 开着没有。没有这一行、没有 enabled、不是 true/false、读出错，一律 false */
    boolean enabled();

    AiSwitchVO get();

    /** 打开或关闭，立刻生效；改前、改后记进这次请求的操作日志 */
    AiSwitchVO set(boolean enabled, Long userId);
}
