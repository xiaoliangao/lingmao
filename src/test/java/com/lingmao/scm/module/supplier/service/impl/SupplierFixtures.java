package com.lingmao.scm.module.supplier.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.module.supplier.dto.SupplierSaveReq;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.entity.SupplierCert;
import com.lingmao.scm.module.supplier.entity.SupplierChange;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.security.LineScope;
import com.lingmao.scm.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** 供应商几个单元测试共用的：登录的人、假数据、MyBatis-Plus 的表信息。数据都是编的 */
final class SupplierFixtures {

    /** 测试里的"今天"：2026-11-15 10:00（东八区） */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    static final String ACCOUNT = "6222000011112222333";

    private SupplierFixtures() {
    }

    /**
     * Lambda 条件、Updates.replaceById 要查 MyBatis-Plus 的表信息缓存，单元测试不起 Spring，手动建一份（见 RoleServiceImplTest）
     */
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Supplier.class);
        TableInfoHelper.initTableInfo(assistant, SupplierCert.class);
        TableInfoHelper.initTableInfo(assistant, SupplierChange.class);
    }

    /** 当前登录的是 userId，带这几个权限码（不带前缀 supplier:supplier:，这里补） */
    static void loginAs(long userId, String... perms) {
        List<SimpleGrantedAuthority> authorities = Stream.concat(Arrays.stream(perms).map(p -> "supplier:supplier:" + p),
                Stream.of(LineScope.ALL_AUTHORITY)).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(userId, "jti-" + userId), null, authorities));
    }

    static void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 一家资料齐全的供应商 */
    static Supplier supplier(long id, SupplierStatus status) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setCode("GYS" + String.format("%04d", id));
        s.setName("测试面料有限公司" + id);
        s.setShortName("测试面料" + id);
        s.setCreditCode(CreditCodeTest.VALID);
        s.setStatus(status);
        s.setContact("联系人甲");
        s.setPhone("13800000000");
        s.setSettleMethod("MONTHLY");
        s.setCreditDays(30);
        s.setInvoiceType("SPECIAL");
        s.setTaxPct(new BigDecimal("13.00"));
        s.setBankName("测试银行某某支行");
        s.setBankAccount(ACCOUNT);
        s.setBankAccountName("测试面料有限公司" + id);
        return s;
    }

    /** 保存请求：照这家现在的样子填（前端打开编辑框就是这样），测试里再改几格 */
    static SupplierSaveReq reqOf(Supplier s) {
        SupplierSaveReq req = new SupplierSaveReq();
        req.setName(s.getName());
        req.setShortName(s.getShortName());
        req.setCreditCode(s.getCreditCode());
        req.setLevel(s.getLevel());
        req.setCategories(List.of("FABRIC"));
        req.setProductLines(s.getProductLines() == null ? List.of("APPAREL") : s.getProductLines());
        req.setContact(s.getContact());
        req.setPhone(s.getPhone());
        req.setSettleMethod(s.getSettleMethod());
        req.setCreditDays(s.getCreditDays());
        req.setInvoiceType(s.getInvoiceType());
        req.setTaxPct(s.getTaxPct());
        req.setBankName(s.getBankName());
        req.setBankAccount(BankAccounts.mask(s.getBankAccount()));
        req.setBankAccountName(s.getBankAccountName());
        return req;
    }
}
