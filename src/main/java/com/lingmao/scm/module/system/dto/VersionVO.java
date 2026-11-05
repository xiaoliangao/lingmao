package com.lingmao.scm.module.system.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.boot.info.BuildProperties;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 正在运行的版本：版本号（如 2026.11.14）和打包时间。
 * 升级脚本靠 version 判断新版本起来了没有；页面底部显示它，甲方报问题时说得清是哪个版本。
 */
public record VersionVO(String version,
                        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
                        OffsetDateTime buildTime) {

    /** 没经过打包脚本（开发环境）时的版本号 */
    public static final String DEV = "dev";

    /**
     * build 来自 jar 里的 META-INF/build-info.properties（pom 里 build-info 生成）；在 IDEA 里直接跑可能没有这个文件，传 null。
     * build.release 是打包脚本用 -Drelease.version 传进来的，平时是 dev
     */
    public static VersionVO from(BuildProperties build) {
        String release = build == null ? null : build.get("release");
        if (release == null || release.isBlank() || DEV.equals(release)) {
            return new VersionVO(DEV, null);
        }
        OffsetDateTime time = build.getTime() == null ? null
                : OffsetDateTime.ofInstant(build.getTime(), ZoneId.of("Asia/Shanghai"));
        return new VersionVO(release, time);
    }
}
