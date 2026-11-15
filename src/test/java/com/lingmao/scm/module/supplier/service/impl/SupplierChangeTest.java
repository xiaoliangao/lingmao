package com.lingmao.scm.module.supplier.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.supplier.dto.SupplierSaveReq;
import com.lingmao.scm.module.supplier.dto.SupplierSaveResult;
import com.lingmao.scm.module.supplier.entity.ChangeItem;
import com.lingmao.scm.module.supplier.entity.ChangeStatus;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.entity.SupplierChange;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.lingmao.scm.module.supplier.entity.SupplierStatus.ACTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 关键字段改动审批（PRD §6.8.2、RFC 004 §4、§15） */
@ExtendWith(MockitoExtension.class)
class SupplierChangeTest {

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

    private SupplierServiceImpl supplierService;
    private SupplierChangeServiceImpl approvals;

    @BeforeAll
    static void initTableInfo() {
        SupplierFixtures.initTableInfo();
    }

    @BeforeEach
    void setUp() {
        SupplierViews views = new SupplierViews(dicts);
        supplierService = new SupplierServiceImpl(supplierMapper, certMapper, changeMapper, changeService, serialNoGenerator,
                docLogService, attachmentService, userService, dicts, views, lists, SupplierFixtures.CLOCK);
        approvals = new SupplierChangeServiceImpl(changeMapper, supplierMapper, docLogService, userService, views,
                SupplierFixtures.CLOCK);
        lenient().when(dicts.labelOf(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
    }

    @AfterEach
    void tearDown() {
        SupplierFixtures.logout();
    }

    /** 规则：合作中的一次改了电话和账期——电话（普通字段）直接生效，账期（关键字段）挂起一份改动，返回里分开说 */
    @Test
    void update_active_phoneAppliedCreditDaysPending() {
        SupplierFixtures.loginAs(7, "edit");
        Supplier current = SupplierFixtures.supplier(1, ACTIVE);
        stubLoaded(current);
        when(supplierMapper.update(any(Supplier.class), any())).thenReturn(1);
        when(changeMapper.exists(any())).thenReturn(false);
        SupplierSaveReq req = SupplierFixtures.reqOf(current);
        req.setPhone("13900000000");
        req.setCreditDays(60);

        SupplierSaveResult result = supplierService.update(1L, req);

        // 普通字段那一次整体覆盖带着新电话；关键字段没有另外的条件更新（只更新了一次）
        ArgumentCaptor<Supplier> saved = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper, times(1)).update(saved.capture(), any());
        assertThat(saved.getValue().getPhone()).isEqualTo("13900000000");
        // 账期挂起：存原值 30 → 60
        ArgumentCaptor<SupplierChange> change = ArgumentCaptor.forClass(SupplierChange.class);
        verify(changeMapper).insert(change.capture());
        assertThat(change.getValue().getStatus()).isEqualTo(ChangeStatus.PENDING);
        assertThat(change.getValue().getApplyBy()).isEqualTo(7L);
        assertThat(change.getValue().getChanges()).containsExactly(new ChangeItem("creditDays", "账期", "30", "60"));
        // 返回给人看的：账期 30 天 → 60 天 等审批，没有直接生效的关键字段
        assertThat(result.applied()).isEmpty();
        assertThat(result.pending()).containsExactly(new ChangeItem("creditDays", "账期", "30 天", "60 天"));
    }

    /** 规则：已经有一份待批的，再改关键字段被拒（1000），不插第二份 */
    @Test
    void update_secondPendingChange_rejected() {
        SupplierFixtures.loginAs(7, "edit");
        Supplier current = SupplierFixtures.supplier(1, ACTIVE);
        stubLoaded(current);
        when(supplierMapper.update(any(Supplier.class), any())).thenReturn(1);
        when(changeMapper.exists(any())).thenReturn(true);
        SupplierSaveReq req = SupplierFixtures.reqOf(current);
        req.setCreditDays(90);

        assertThatThrownBy(() -> supplierService.update(1L, req))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getMessage()).contains("已经有一份待批的改动");
                });
        verify(changeMapper, never()).insert(any(SupplierChange.class));
    }

    /** 规则：批准时 before 和现在的值对不上（账期被别的途径改成了 45）→ 1002，不写回 */
    @Test
    void approve_beforeMismatch_stateChanged() {
        SupplierFixtures.loginAs(8, "admit");
        when(changeMapper.selectById(5L)).thenReturn(pendingChange());
        when(changeMapper.update(any(SupplierChange.class), any())).thenReturn(1);
        Supplier current = SupplierFixtures.supplier(1, ACTIVE);
        current.setCreditDays(45);
        when(supplierMapper.selectByIdForUpdate(1L)).thenReturn(current);

        assertThatThrownBy(() -> approvals.approve(1L, 5L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED));
        verify(supplierMapper, never()).update(any(Supplier.class), any());
    }

    /** 规则：批准后写回供应商（SET credit_days = 60），操作记录写"账期 30 天 → 60 天" */
    @Test
    void approve_writesBack() {
        SupplierFixtures.loginAs(8, "admit");
        when(changeMapper.selectById(5L)).thenReturn(pendingChange());
        when(changeMapper.update(any(SupplierChange.class), any())).thenReturn(1);
        when(supplierMapper.selectByIdForUpdate(1L)).thenReturn(SupplierFixtures.supplier(1, ACTIVE));

        approvals.approve(1L, 5L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Supplier>> written = ArgumentCaptor.forClass(Wrapper.class);
        verify(supplierMapper).update(any(Supplier.class), written.capture());
        LambdaUpdateWrapper<Supplier> wrapper = (LambdaUpdateWrapper<Supplier>) written.getValue();
        assertThat(wrapper.getSqlSet()).contains("credit_days");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(60);
        verify(docLogService).add(BizType.SUPPLIER, 1L, "change-approve", "账期 30 天 → 60 天");
    }

    /** 规则：提改动的人自己批被拒 */
    @Test
    void approve_byApplicant_rejected() {
        SupplierFixtures.loginAs(7, "admit");
        when(changeMapper.selectById(5L)).thenReturn(pendingChange());

        assertThatThrownBy(() -> approvals.approve(1L, 5L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("另一个有准入权限的人");
        verify(changeMapper, never()).update(any(SupplierChange.class), any());
    }

    private void stubLoaded(Supplier current) {
        when(supplierMapper.selectByIdForUpdate(current.getId())).thenReturn(current);
        when(supplierMapper.selectCategories(current.getId())).thenReturn(List.of("FABRIC"));
        when(supplierMapper.selectGoodAtOf(List.of(current.getId()))).thenReturn(List.of());
    }

    /** 1 号供应商的一份待批改动：7 号提的，账期 30 → 60 */
    private static SupplierChange pendingChange() {
        SupplierChange change = new SupplierChange();
        change.setId(5L);
        change.setSupplierId(1L);
        change.setStatus(ChangeStatus.PENDING);
        change.setApplyBy(7L);
        change.setChanges(List.of(new ChangeItem("creditDays", "账期", "30", "60")));
        return change;
    }
}
