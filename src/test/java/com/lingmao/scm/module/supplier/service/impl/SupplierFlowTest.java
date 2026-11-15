package com.lingmao.scm.module.supplier.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.mapper.SupplierCertMapper;
import com.lingmao.scm.module.supplier.mapper.SupplierChangeMapper;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import com.lingmao.scm.module.supplier.service.SupplierChangeService;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static com.lingmao.scm.module.supplier.entity.SupplierStatus.ADMITTING;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.BLACKLISTED;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.DISABLED;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.PENDING_ADMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 供应商的状态动作（PRD §6.8.2、RFC 004 §2、§15） */
@ExtendWith(MockitoExtension.class)
class SupplierFlowTest {

    @Mock
    private GoodAtCategories goodAtCategories;
    @Mock
    private WarehouseService warehouseService;
    @Mock
    private SupplierMapper supplierMapper;
    @Mock
    private SupplierCertMapper certMapper;
    @Mock
    private SupplierChangeMapper changeMapper;
    @Mock
    private SupplierChangeService changeService;
    @Mock
    private SerialNoGenerator serialNoGenerator;
    @Mock
    private DocLogService docLogService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private UserService userService;
    @Mock
    private SupplierDicts dicts;
    @Mock
    private SupplierLists lists;

    private SupplierServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        SupplierFixtures.initTableInfo();
    }

    @BeforeEach
    void setUp() {
        service = new SupplierServiceImpl(supplierMapper, certMapper, changeMapper, changeService, serialNoGenerator,
                docLogService, attachmentService, userService, dicts, new SupplierViews(dicts), lists, goodAtCategories, warehouseService,
                SupplierFixtures.CLOCK);
    }

    @AfterEach
    void tearDown() {
        SupplierFixtures.logout();
    }

    /** 规则：缺信用代码和营业执照时申请准入，1000，data 一次列出这两项；状态不改 */
    @Test
    void submit_missingCreditAndLicense_listsBoth() {
        SupplierFixtures.loginAs(7, "edit");
        Supplier s = SupplierFixtures.supplier(1, PENDING_ADMIT);
        s.setCreditCode(null);
        when(supplierMapper.selectByIdForUpdate(1L)).thenReturn(s);
        when(certMapper.exists(any())).thenReturn(false);

        assertThatThrownBy(() -> service.submit(1L))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getMessage()).contains("还缺 2 项");
                    assertThat((List<?>) e.getData()).hasSize(2);
                    assertThat(e.getData().toString()).contains("统一社会信用代码").contains("营业执照");
                });
        verify(supplierMapper, never()).update(any(Supplier.class), any());
    }

    /** 规则：申请人自己点通过被拒（超级管理员也一样），状态不改 */
    @Test
    void audit_byApplicant_rejected() {
        SupplierFixtures.loginAs(7, "admit");
        Supplier s = SupplierFixtures.supplier(1, ADMITTING);
        s.setAdmitApplyBy(7L);
        when(supplierMapper.selectById(1L)).thenReturn(s);

        assertThatThrownBy(() -> service.audit(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("另一个有准入权限的人");
        verify(supplierMapper, never()).update(any(Supplier.class), any());
    }

    /** 规则：准入审批中保存被拒（1000"先撤回"），一个字段都不写 */
    @Test
    void update_whileAdmitting_rejected() {
        SupplierFixtures.loginAs(7, "edit");
        Supplier s = SupplierFixtures.supplier(1, ADMITTING);
        when(supplierMapper.selectByIdForUpdate(1L)).thenReturn(s);

        assertThatThrownBy(() -> service.update(1L, SupplierFixtures.reqOf(s)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getMessage()).isEqualTo("准入审批中不能改，先撤回");
                });
        verify(supplierMapper, never()).update(any(Supplier.class), any());
        verify(supplierMapper, never()).deleteCategories(1L);
    }

    /** 规则：从没准入过的（admit_time 空）解除黑名单回待准入，不回停用；原因进操作记录 */
    @Test
    void unblacklist_neverAdmitted_backToPendingAdmit() {
        SupplierFixtures.loginAs(7, "admit");
        Supplier s = SupplierFixtures.supplier(1, BLACKLISTED);
        when(supplierMapper.selectById(1L)).thenReturn(s);
        when(supplierMapper.update(any(Supplier.class), any())).thenReturn(1);

        service.unblacklist(1L, "误拉黑");

        verify(docLogService).add(BizType.SUPPLIER, 1L, "unblacklist", BLACKLISTED, PENDING_ADMIT, "误拉黑");
    }

    /** 规则：准入过的解除黑名单回停用 */
    @Test
    void unblacklist_admitted_backToDisabled() {
        SupplierFixtures.loginAs(7, "admit");
        Supplier s = SupplierFixtures.supplier(1, BLACKLISTED);
        s.setAdmitTime(OffsetDateTime.parse("2026-11-01T10:00:00+08:00"));
        when(supplierMapper.selectById(1L)).thenReturn(s);
        when(supplierMapper.update(any(Supplier.class), any())).thenReturn(1);

        service.unblacklist(1L, "复核没问题");

        verify(docLogService).add(eq(BizType.SUPPLIER), eq(1L), eq("unblacklist"), eq(BLACKLISTED), eq(DISABLED), any());
    }

    /** 规则：详情的按钮——申请人看不到通过、驳回，只能撤回；别人有准入权限的看得到通过、驳回，看不到撤回 */
    @Test
    void actions_applicantVsApprover() {
        Supplier s = SupplierFixtures.supplier(1, ADMITTING);
        s.setAdmitApplyBy(7L);

        SupplierFixtures.loginAs(7, "edit", "admit");
        assertThat(service.actions(s)).contains("withdraw", "blacklist").doesNotContain("audit", "reject", "edit", "remove");

        SupplierFixtures.loginAs(8, "edit", "admit");
        assertThat(service.actions(s)).contains("audit", "reject").doesNotContain("withdraw");
    }
}
