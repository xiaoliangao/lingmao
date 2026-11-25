package com.lingmao.scm.module.system.aspect;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 带幂等的新建接口（@IdempotentCreate）失败时，操作日志里要留下那条"失败"：
 * 日志切面排在幂等事务外面，业务回滚不带走它（OperLogAspect 开头的说明）。
 */
@IntegrationTest
@AutoConfigureMockMvc
class OperLogIdempotencyIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    @Test
    void failedCreateKeepsItsFailureLogAndSuccessIsLoggedOnce() throws Exception {
        long user = jdbc.queryForObject("INSERT INTO sys_user(username, password, nickname) VALUES (?, 'x', '操作日志 IT') RETURNING id",
                Long.class, "oper-log-" + UUID.randomUUID());
        String name = "日志测试供应商" + UUID.randomUUID().toString().substring(0, 8);

        // 信用代码不是 18 位：进了业务方法才报错，幂等事务回滚
        JsonNode failed = create(user, supplier(name, "123"));
        assertThat(failed.path("code").intValue()).as(failed.toString()).isNotZero();
        assertThat(failed.path("msg").stringValue()).contains("18 位");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sup_supplier WHERE name = ?", Integer.class, name)).isZero();

        JsonNode saved = create(user, supplier(name, null));
        assertThat(saved.path("code").intValue()).as(saved.toString()).isZero();

        List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT success, error_msg, method FROM sys_oper_log WHERE user_id = ? ORDER BY id", user);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0)).containsEntry("success", false).containsEntry("method", "SupplierController.create");
        assertThat(logs.get(0)).containsEntry("error_msg", "Business error 1000");   // 只记错误码，不记原文（errorMessage）
        assertThat(logs.get(1)).containsEntry("success", true).containsEntry("method", "SupplierController.create");
    }

    private static Map<String, Object> supplier(String name, String creditCode) {
        var body = new java.util.HashMap<String, Object>(Map.of(
                "name", name, "shortName", name.substring(0, 6), "categories", List.of("FABRIC"), "productLines", List.of("APPAREL")));
        body.put("creditCode", creditCode);
        return body;
    }

    private JsonNode create(long user, Map<String, Object> body) throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(new LoginUser(user, "oper-log-it"), null,
                TestIdentity.withLines(List.of("supplier:supplier:add", "supplier:supplier:list")).stream()
                        .map(SimpleGrantedAuthority::new).toList());
        var request = post("/api/suppliers").with(authentication(auth))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }
}
