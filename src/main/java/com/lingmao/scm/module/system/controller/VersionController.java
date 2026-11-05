package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.VersionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "版本")
@RestController
@RequestMapping("/api/version")
public class VersionController {

    private final VersionVO version;

    /**
     * 版本号在 jar 里，运行期间不会变，启动时算一次。
     * ObjectProvider：jar 里没有 build-info.properties 时（IDEA 里直接跑）Spring 不提供 BuildProperties，这时拿到 null，返回 dev
     */
    public VersionController(ObjectProvider<BuildProperties> buildProperties) {
        this.version = VersionVO.from(buildProperties.getIfAvailable());
    }

    /** 不用登录（SecurityConfig 放行）：升级脚本在登录之前就要用它判断新版本起来了没有 */
    @Operation(summary = "当前版本号和打包时间（不用登录）")
    @GetMapping
    public R<VersionVO> version() {
        return R.ok(version);
    }
}
