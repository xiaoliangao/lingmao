package com.lingmao.scm.security;

import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.FieldChangeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldProjectionTest {

    private static final String SUPPLIER_CONTROLLER = "com.lingmao.scm.module.supplier.controller.SupplierController";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 规则（RFC 016 §3，agy 复查）：供应商操作记录里遮过的改动项（DocLogServiceImpl 已置 null + hidden）
     * 投影时留着 before / after 两个键（值是 null），和物料、款式、接口文档一致
     */
    @Test
    void supplierLog_hiddenChangeKeepsNullKeys() {
        DocLogVO log = new DocLogVO(1L, "update", null, null, null, 1L, "甲", OffsetDateTime.now(), List.of(
                new FieldChangeVO("phone", "电话", null, null, true),
                new FieldChangeVO("shortName", "简称", "旧", "新", false)));

        JsonNode diff = new FieldProjection(JsonMapper.builder().build()).project(List.of(log), SUPPLIER_CONTROLLER).get(0).get("diff");

        assertThat(diff.get(0).has("before")).isTrue();
        assertThat(diff.get(0).get("before").isNull()).isTrue();
        assertThat(diff.get(0).has("after")).isTrue();
        assertThat(diff.get(0).get("hidden").asBoolean()).isTrue();
        assertThat(diff.get(1).get("after").asString()).isEqualTo("新");
    }
}
