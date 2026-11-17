package com.lingmao.scm.module.techpack.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 生效时带回打开时（或刚保存完）的 versionLock：这期间别人改过就 1002 */
@Data
public class VersionLockReq {
    @NotNull(message = "缺少 versionLock，请刷新后再试")
    private Integer versionLock;
}
