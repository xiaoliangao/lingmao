package com.lingmao.scm.module.price.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.price.dto.PriceSaveReq;
import com.lingmao.scm.module.price.entity.Price;
import com.lingmao.scm.module.price.entity.PriceType;
import com.lingmao.scm.module.price.mapper.PriceMapper;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.supplier.service.SupplierService.Usage;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 录价、作废的规则（PRD §6.10 规则 1、5，RFC 006 §3、§12）。取现价的 SQL 本身在 PriceServiceImplIT 测。数据都是编的
 */
@ExtendWith(MockitoExtension.class)
class PriceServiceImplTest {

    /** 测试里的"今天"：2026-11-15（东八区） */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    private static final long SUPPLIER = 1L;
    private static final long MATERIAL = 10L;
    private static final long SKU_KHAKI = 101L;

    @Mock
    private PriceMapper priceMapper;
    @Mock
    private SupplierService supplierService;
    @Mock
    private MaterialService materialService;
    @Mock
    private StyleService styleService;
    @Mock
    private DictDataService dictDataService;
    @Mock
    private UserService userService;

    private PriceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PriceServiceImpl(priceMapper, supplierService, materialService, styleService, dictDataService, userService, CLOCK);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：同一层级已经有现价（物料层 18.50），改成 19.00 不写原因 → 1000，没插 */
    @Test
    void record_currentExistsWithoutReason_rejected() {
        loginAs("price:material:edit");
        stubSupplier();
        when(materialService.materialRefs(List.of(MATERIAL))).thenReturn(List.of(material(1)));
        when(priceMapper.selectCurrentMaterial(SUPPLIER, MATERIAL, null, "METER", TODAY))
                .thenReturn(price("18.5000", LocalDate.of(2026, 11, 1), null));

        assertThatThrownBy(() -> service.record(materialReq("19.00", null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getMessage()).isEqualTo("现价 18.5（11-01 起）→ 19，要写调价原因");
                });
        verify(priceMapper, never()).insert(any(Price.class));
    }

    /** 规则：和同一层级的现价一样 → 1000"不用再录"，写了原因也不行 */
    @Test
    void record_samePriceAsCurrent_rejected() {
        loginAs("price:material:edit");
        stubSupplier();
        when(materialService.materialRefs(List.of(MATERIAL))).thenReturn(List.of(material(1)));
        when(priceMapper.selectCurrentMaterial(SUPPLIER, MATERIAL, null, "METER", TODAY))
                .thenReturn(price("18.5000", LocalDate.of(2026, 11, 1), null));

        assertThatThrownBy(() -> service.record(materialReq("18.50", null, "又报了一次")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getMessage()).isEqualTo("11-15 的现价已经是 18.5（11-01 起），不用再录");
                });
        verify(priceMapper, never()).insert(any(Price.class));
    }

    /**
     * 规则：第一次给卡其单独定价，现价是物料层的 19.00，不算同一层级：原因可以不写。
     * 记到 SKU 上；单位是物料现在的采购单位，税率带供应商的，含税、含运默认是，生效日期默认今天
     */
    @Test
    void record_firstSkuPrice_noReasonNeeded() {
        loginAs("price:material:edit");
        stubSupplier();
        when(materialService.skuRefs(List.of(SKU_KHAKI))).thenReturn(List.of(sku(SKU_KHAKI, material(1), 1)));
        when(priceMapper.selectCurrentMaterial(SUPPLIER, MATERIAL, SKU_KHAKI, "METER", TODAY))
                .thenReturn(price("19.0000", LocalDate.of(2026, 11, 1), null));
        when(priceMapper.insert(any(Price.class))).thenAnswer(inv -> {
            inv.<Price>getArgument(0).setId(77L);
            return 1;
        });

        long id = service.record(materialReq("19.50", SKU_KHAKI, "  "));

        assertThat(id).isEqualTo(77L);
        ArgumentCaptor<Price> saved = ArgumentCaptor.forClass(Price.class);
        verify(priceMapper).insert(saved.capture());
        Price p = saved.getValue();
        assertThat(p.getType()).isEqualTo(PriceType.MATERIAL);
        assertThat(p.getMaterialId()).isEqualTo(MATERIAL);
        assertThat(p.getMaterialSkuId()).isEqualTo(SKU_KHAKI);
        assertThat(p.getUnit()).isEqualTo("METER");
        assertThat(p.getPrice()).isEqualByComparingTo("19.50");
        assertThat(p.getTaxPct()).isEqualByComparingTo("13");
        assertThat(p.getTaxIncluded()).isTrue();
        assertThat(p.getFreightIncluded()).isTrue();
        assertThat(p.getEffectiveDate()).isEqualTo(TODAY);
        assertThat(p.getReason()).isNull();
        assertThat(p.getStyleId()).isNull();
        assertThat(p.getProcessMode()).isNull();
    }

    /** 规则：黑名单（停用）的供应商不能录：supplier 的 checkUsable(SAMPLE) 拦住，原样报给前端，没插 */
    @Test
    void record_blacklistedSupplier_rejected() {
        loginAs("price:material:edit");
        doThrow(new BizException("测试面料 现在是「黑名单」，不能用来打样、录价格"))
                .when(supplierService).checkUsable(SUPPLIER, Usage.SAMPLE);

        assertThatThrownBy(() -> service.record(materialReq("19.00", null, null)))
                .isInstanceOf(BizException.class)
                .hasMessage("测试面料 现在是「黑名单」，不能用来打样、录价格");
        verify(priceMapper, never()).insert(any(Price.class));
        verifyNoInteractions(materialService);
    }

    /** 规则：只有加工价权限（生产跟单）的人录物料价 → 403，什么都不查 */
    @Test
    void record_materialPriceWithOnlyProcessPermission_returns403() {
        loginAs("price:process:edit");

        assertThatThrownBy(() -> service.record(materialReq("19.00", null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        verifyNoInteractions(supplierService, materialService);
        verify(priceMapper, never()).insert(any(Price.class));
    }

    /** 规则：加工价第一次报价不用原因，单位是件；加工方式只认字典里启用的 */
    @Test
    void record_processPrice_unitIsPieceAndModeMustBeInDict() {
        loginAs("price:process:edit");
        stubSupplier();
        when(dictDataService.listByType("process_mode")).thenReturn(List.of(
                new DictOption("包工包料", "FULL", 1), new DictOption("清加工", "CMT", 1), new DictOption("半包", "HALF", 0)));
        when(priceMapper.selectCurrentProcess(SUPPLIER, 500L, "CMT", TODAY)).thenReturn(null);
        when(priceMapper.insert(any(Price.class))).thenAnswer(inv -> {
            inv.<Price>getArgument(0).setId(78L);
            return 1;
        });

        PriceSaveReq cmt = processReq("CMT");
        cmt.setTaxPct(new BigDecimal("3"));
        service.record(cmt);

        ArgumentCaptor<Price> saved = ArgumentCaptor.forClass(Price.class);
        verify(priceMapper).insert(saved.capture());
        assertThat(saved.getValue().getUnit()).isEqualTo("PIECE");
        assertThat(saved.getValue().getTaxPct()).isEqualByComparingTo("3");
        assertThat(saved.getValue().getMaterialId()).isNull();

        assertThatThrownBy(() -> service.record(processReq("HALF")))
                .isInstanceOf(BizException.class).hasMessageContaining("加工方式「HALF」不在选项里");
    }

    /** 规则：作废是条件更新（AND NOT voided），改到 0 行 = 已经作废了 → 1002 */
    @Test
    void voidPrice_alreadyVoided_returns1002() {
        loginAs("price:material:edit");
        Price p = price("18.5000", LocalDate.of(2026, 11, 1), null);
        p.setId(9L);
        when(priceMapper.selectById(9L)).thenReturn(p);
        when(priceMapper.voidById(eq(9L), eq("录错了"), eq(7L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.voidPrice(9L, " 录错了 "))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED);
                    assertThat(e.getMessage()).isEqualTo("这条已经作废了");
                });
    }

    /** 规则：停用的款式不能录加工价（RFC 006 §3），StyleService 说为什么，不插 */
    @Test
    void record_processForDisabledStyle_rejected() {
        loginAs("price:process:edit");
        stubSupplier();
        doThrow(new BizException("款式 FS2611080701 现在是「停用」，不能再用")).when(styleService).checkUsable(500L);

        assertThatThrownBy(() -> service.record(processReq("CMT")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getMessage()).contains("停用");
                });
        verify(priceMapper, never()).insert(any(Price.class));
    }

    /** 规则：作废按那条的类型查权限：只有物料价权限的人作废加工价 → 403，不改库 */
    @Test
    void voidPrice_processPriceWithOnlyMaterialPermission_returns403() {
        loginAs("price:material:edit");
        Price p = price("38.0000", LocalDate.of(2026, 10, 25), null);
        p.setType(PriceType.PROCESS);
        when(priceMapper.selectById(9L)).thenReturn(p);

        assertThatThrownBy(() -> service.voidPrice(9L, "录错了"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        verify(priceMapper, never()).voidById(anyLong(), any(), any(), any());
    }

    // ------------------------------------------------------------------ 假数据

    private static void loginAs(String... perms) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(7L, "jti-7"), null, Arrays.stream(perms).map(SimpleGrantedAuthority::new).toList()));
    }

    private void stubSupplier() {
        when(supplierService.getRef(SUPPLIER)).thenReturn(new SupplierRef(SUPPLIER, "GYS0001", "测试面料有限公司", "测试面料",
                SupplierStatus.ACTIVE, new BigDecimal("13.00"), "MONTHLY", 30, "SPECIAL"));
    }

    /** 一个面料：库存、采购单位都是米 */
    private static MaterialRef material(int status) {
        return new MaterialRef(MATERIAL, "ML0012", "测试斜纹棉", "FABRIC", "METER", "METER", BigDecimal.ONE,
                new BigDecimal("18.00"), "PURCHASE", BigDecimal.ZERO, BigDecimal.ZERO, null, status);
    }

    private static MaterialSkuRef sku(long id, MaterialRef material, int status) {
        return new MaterialSkuRef(id, "ML0012-KQ", material, 3L, "KQ", "卡其", null, null, null, null, status);
    }

    private static Price price(String value, LocalDate date, Long skuId) {
        Price p = new Price();
        p.setId(1L);
        p.setType(PriceType.MATERIAL);
        p.setSupplierId(SUPPLIER);
        p.setMaterialId(MATERIAL);
        p.setMaterialSkuId(skuId);
        p.setPrice(new BigDecimal(value));
        p.setUnit("METER");
        p.setTaxPct(new BigDecimal("13.00"));
        p.setTaxIncluded(true);
        p.setFreightIncluded(true);
        p.setEffectiveDate(date);
        p.setVoided(false);
        return p;
    }

    private static PriceSaveReq materialReq(String price, Long skuId, String reason) {
        PriceSaveReq req = new PriceSaveReq();
        req.setType(PriceType.MATERIAL);
        req.setSupplierId(SUPPLIER);
        req.setMaterialId(MATERIAL);
        req.setMaterialSkuId(skuId);
        req.setPrice(new BigDecimal(price));
        req.setReason(reason);
        return req;
    }

    private static PriceSaveReq processReq(String mode) {
        PriceSaveReq req = new PriceSaveReq();
        req.setType(PriceType.PROCESS);
        req.setSupplierId(SUPPLIER);
        req.setStyleId(500L);
        req.setProcessMode(mode);
        req.setPrice(new BigDecimal("38"));
        return req;
    }
}
