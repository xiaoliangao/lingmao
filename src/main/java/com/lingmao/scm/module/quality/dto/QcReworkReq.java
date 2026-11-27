package com.lingmao.scm.module.quality.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 安排返修、返修完成
 *
 * @param version 打开时拿到的版本号
 * @param note    返修说明：返修什么、多少件、工厂哪天送回；安排返修时必填，返修完成不用
 */
public record QcReworkReq(@NotNull(message = "版本号必填") Integer version,
                          @Size(max = 500, message = "返修说明最多 500 个字") String note) {
}
