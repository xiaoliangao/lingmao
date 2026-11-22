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

    record Place(String name, String address, String phone, String warehouseAddress, String invoiceAddressPhone,
                 String factoryContact, String remark, java.util.Map<String, String> legacyAttrs, Integer contactQty, String addressee) {
    }

    /**
     * 规则（用户 11-22 定联系方式不发给外部模型，agy 复查 T-a43c80 #3）：助手令牌的请求，谁的地址、电话、联系人都去掉
     * （工厂仓的地址就是加工厂的收货地址），就算这个人有看联系方式的权限；名称、备注照给。普通登录不受影响
     */
    @Test
    void assistant_dropsEveryAddressPhoneContact() {
        Place p = new Place("一号工厂仓", "示例路 1 号", "0571-0000", "示例路 1 号", "示例开票地址 0571-0000", "张三", "放门口",
                java.util.Map.of("地址电话", "示例路 1 号 0571", "外部编码", "X1"), 3, "收件部门");
        String controller = "com.lingmao.scm.module.basic.controller.WarehouseController";
        FieldProjection projection = new FieldProjection(JsonMapper.builder().build());

        as(FieldPermissions.CONTACT);
        JsonNode mine = projection.project(p, controller);
        assertThat(mine.get("address").asString()).isEqualTo("示例路 1 号");
        assertThat(mine.get("factoryContact").asString()).isEqualTo("张三");

        as(FieldPermissions.CONTACT, com.lingmao.scm.common.auth.CallerScope.ASSISTANT_AUTHORITY);
        JsonNode theirs = projection.project(p, controller);
        assertThat(theirs.has("address") || theirs.has("phone") || theirs.has("warehouseAddress") || theirs.has("invoiceAddressPhone")
                || theirs.has("factoryContact")).isFalse();
        assertThat(theirs.get("legacyAttrs").has("地址电话")).isFalse();
        assertThat(theirs.get("legacyAttrs").get("外部编码").asString()).isEqualTo("X1");
        assertThat(theirs.get("name").asString()).isEqualTo("一号工厂仓");
        assertThat(theirs.get("remark").asString()).isEqualTo("放门口");
        // 整个名字对上才去掉：contactQty、addressee 这种不相干的留着（防以后把 matches 改成 find）
        assertThat(theirs.get("contactQty").asInt()).isEqualTo(3);
        assertThat(theirs.get("addressee").asString()).isEqualTo("收件部门");
    }

    /**
     * 规则（agy 复查 T-0a5c40 #2）：供应商待批的关键字段改动，助手看不到银行、联系方式的前后值，别的字段照给
     */
    @Test
    void assistant_pendingChangeHidesBankAndContact() {
        com.lingmao.scm.module.supplier.dto.ChangeVO change = new com.lingmao.scm.module.supplier.dto.ChangeVO(1L, null, List.of(
                new com.lingmao.scm.module.supplier.entity.ChangeItem("bankAccount", "账号", "6222000011112222", "6222000033334444"),
                new com.lingmao.scm.module.supplier.entity.ChangeItem("phone", "电话", "0571-1", "0571-2"),
                new com.lingmao.scm.module.supplier.entity.ChangeItem("shortName", "简称", "旧", "新")),
                1L, "甲", OffsetDateTime.now(), null, null, null, List.of());

        as(com.lingmao.scm.common.auth.CallerScope.ASSISTANT_AUTHORITY);
        JsonNode items = new FieldProjection(JsonMapper.builder().build()).project(change, SUPPLIER_CONTROLLER).get("items");

        assertThat(items.get(0).has("before") || items.get(0).has("after")).isFalse();
        assertThat(items.get(1).has("before") || items.get(1).has("after")).isFalse();
        assertThat(items.get(2).get("after").asString()).isEqualTo("新");
        assertThat(items.toString()).doesNotContain("6222", "0571");
    }

    private static void as(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "u", null, java.util.Arrays.stream(authorities).map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList()));
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
