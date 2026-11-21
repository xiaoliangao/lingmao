package com.lingmao.scm.config;

import com.jayway.jsonpath.JsonPath;
import com.lingmao.scm.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 真起应用导出一次接口文档（和发版导出 openapi.json 是同一份），看拆查询对象（default-flat-param-object）没把东西拆坏：
 * 上传、导入接口的 file 还在、是 multipart；当前登录的人没拆成参数；查询对象拆成了带说明的查询参数
 */
@IntegrationTest
class OpenApiDocsIT {
    @Autowired
    private WebApplicationContext context;

    @Test
    void apiDocs_flattenedWithoutLosingUploads() throws Exception {
        String docs = MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        for (String upload : List.of("/api/files", "/api/materials/import", "/api/orders/import", "/api/styles/import",
                "/api/suppliers/import", "/api/tech-packs/{id}/bom-import", "/api/inventory-counts/{id}/import",
                "/api/fg-docs/{id}/items/import")) {
            assertThat((String) JsonPath.read(docs, "$.paths['" + upload + "'].post.requestBody.content['multipart/form-data']"
                    + ".schema.properties.file.format")).as(upload).isEqualTo("binary");
        }
        List<String> orderImportParams = JsonPath.read(docs, "$.paths['/api/orders/import'].post.parameters[*].name");
        assertThat(orderImportParams).contains("companyId", "orderType").doesNotContain("file");
        List<String> userParams = JsonPath.read(docs, "$.paths..parameters[?(@.name == 'jti')].name");
        assertThat(userParams).isEmpty();
        List<String> pageDocs = JsonPath.read(docs, "$.paths['/api/warehouses'].get.parameters[?(@.name == 'page')].description");
        assertThat(pageDocs).containsExactly("第几页，从 1 开始，默认 1");
    }
}
