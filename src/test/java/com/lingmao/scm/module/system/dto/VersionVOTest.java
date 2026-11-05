package com.lingmao.scm.module.system.dto;

import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class VersionVOTest {

    /** 规则：jar 里没有打包信息（IDEA 里直接跑）→ 版本号 dev */
    @Test
    void from_noBuildInfo_isDev() {
        assertThat(VersionVO.from(null)).isEqualTo(new VersionVO("dev", null));
    }

    /** 规则：mvn 打的包，但不是打包脚本打的（没传 -Drelease.version）→ 也是 dev，不带打包时间 */
    @Test
    void from_releaseNotPassed_isDev() {
        Properties props = new Properties();
        props.setProperty("release", "dev");
        props.setProperty("time", "2026-11-13T13:05:00Z");

        assertThat(VersionVO.from(new BuildProperties(props))).isEqualTo(new VersionVO("dev", null));
    }
}
