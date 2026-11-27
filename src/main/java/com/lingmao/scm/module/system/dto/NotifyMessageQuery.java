package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 最近发的钉钉消息，新的在前 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NotifyMessageQuery extends PageQuery {
    /** 只看发失败的（DEAD_LETTER：重试完了、收件人找不到）；不传看全部 */
    private Boolean failed;
}
