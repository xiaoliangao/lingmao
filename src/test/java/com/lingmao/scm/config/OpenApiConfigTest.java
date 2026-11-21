package com.lingmao.scm.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 接口文档里没有说明的参数，按名字补通用说明；写了的不动、认不出的查询参数不编 */
class OpenApiConfigTest {

    @Test
    void fallback_byName() {
        assertThat(OpenApiConfig.fallback("warehouseId")).isEqualTo("按仓库 id 筛");
        assertThat(OpenApiConfig.fallback("materialSkuId")).isEqualTo("按物料 SKU id 筛");
        assertThat(OpenApiConfig.fallback("styleIds")).isEqualTo("按这几个款式 id 筛");
        assertThat(OpenApiConfig.fallback("dueDateTo")).isEqualTo("交期到这天为止（含）");
        assertThat(OpenApiConfig.fallback("createdDateFrom")).isEqualTo("创建日期从这天起（含）");
        assertThat(OpenApiConfig.fallback("auditForMe")).isEqualTo("true：只看等我审核的");
        assertThat(OpenApiConfig.fallback("whateverId")).isNull();
        assertThat(OpenApiConfig.fallback("techPackStatus")).isNull();
    }

    @Test
    void customizer_fillsOnlyMissingDescriptions() {
        Parameter written = new Parameter().name("warehouseId").in("query").description("出库的仓库");
        Parameter missing = new Parameter().name("warehouseId").in("query");
        Parameter path = new Parameter().name("styleId").in("path");
        Parameter self = new Parameter().name("id").in("path");
        OpenAPI api = new OpenAPI().paths(new Paths().addPathItem("/api/x",
                new PathItem().get(new Operation().parameters(List.of(written, missing, path, self)))));

        new OpenApiConfig().parameterFallbacks().customise(api);

        assertThat(written.getDescription()).isEqualTo("出库的仓库");
        assertThat(missing.getDescription()).isEqualTo("按仓库 id 筛");
        assertThat(path.getDescription()).isEqualTo("款式的 id");
        assertThat(self.getDescription()).isEqualTo("地址里这一条的 id");
    }
}
