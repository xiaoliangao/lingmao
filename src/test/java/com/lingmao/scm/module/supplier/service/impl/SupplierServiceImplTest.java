package com.lingmao.scm.module.supplier.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.supplier.dto.SupplierSaveReq;
import com.lingmao.scm.module.supplier.dto.SupplierSaveResult;
import com.lingmao.scm.module.supplier.dto.SupplierVO;
import com.lingmao.scm.module.supplier.entity.ChangeItem;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.entity.SupplierChange;
import com.lingmao.scm.module.supplier.mapper.SupplierCertMapper;
import com.lingmao.scm.module.supplier.mapper.SupplierChangeMapper;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import com.lingmao.scm.module.supplier.service.SupplierChangeService;
import com.lingmao.scm.module.supplier.service.SupplierService.Usage;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static com.lingmao.scm.module.supplier.entity.SupplierStatus.ACTIVE;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.BLACKLISTED;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.PENDING_ADMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商的保存、详情、能不能选（PRD §6.1.6、§6.8、Q30、RFC 004 §15）。
 * "分类加了加工厂时调 syncFactoryWarehouse"等工厂仓接上（第 3 步）再补，见 RFC 004 §17
 */
@ExtendWith(MockitoExtension.class)
class SupplierServiceImplTest {

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
                docLogService, attachmentService, userService, dicts, new SupplierViews(dicts), lists, SupplierFixtures.CLOCK);
    }

    @AfterEach
    void tearDown() {
        SupplierFixtures.logout();
    }

    /** 规则：没有 bank 权限的人保存时，请求里的账号不收（传了别的号也不改、不挂起改动）（RFC 004 §5） */
    @Test
    void update_withoutBankPermission_accountIgnored() {
        SupplierFixtures.loginAs(7, "edit");
        Supplier current = SupplierFixtures.supplier(1, ACTIVE);
        stubLoaded(current);
        when(supplierMapper.update(any(Supplier.class), any())).thenReturn(1);
        SupplierSaveReq req = SupplierFixtures.reqOf(current);
        req.setBankAccount("6217000099998888777");

        SupplierSaveResult result = service.update(1L, req);

        assertThat(result.pending()).isEmpty();
        assertThat(result.applied()).isEmpty();
        verify(changeMapper, never()).insert(any(SupplierChange.class));
        verify(supplierMapper, times(1)).update(any(Supplier.class), any());
    }

    /** 规则：有 bank 权限的人把遮过的样子（6222****3333）原样传回来，当作没改，不会把遮过的值存进去 */
    @Test
    void update_withBankPermission_maskedValueMeansUnchanged() {
        SupplierFixtures.loginAs(7, "edit", "bank");
        Supplier current = SupplierFixtures.supplier(1, PENDING_ADMIT);
        stubLoaded(current);
        when(supplierMapper.update(any(Supplier.class), any())).thenReturn(1);
        SupplierSaveReq req = SupplierFixtures.reqOf(current);
        assertThat(req.getBankAccount()).isEqualTo("6222****2333");

        SupplierSaveResult result = service.update(1L, req);

        assertThat(result.applied()).isEmpty();
        verify(supplierMapper, times(1)).update(any(Supplier.class), any());
    }

    /** 规则：合作中的供应商，信用代码从空补上的直接生效，不挂起（用户 11-15 定，Q29）；输入的小写、空格规整掉 */
    @Test
    void update_activeCreditFromEmpty_appliedDirectly() {
        SupplierFixtures.loginAs(7, "edit");
        Supplier current = SupplierFixtures.supplier(1, ACTIVE);
        current.setCreditCode(null);
        stubLoaded(current);
        when(supplierMapper.update(any(Supplier.class), any())).thenReturn(1);
        SupplierSaveReq req = SupplierFixtures.reqOf(current);
        req.setCreditCode("91000000ma0000002w ");

        SupplierSaveResult result = service.update(1L, req);

        assertThat(result.applied()).containsExactly(
                new ChangeItem("creditCode", "统一社会信用代码", "空", "91000000MA0000002W"));
        assertThat(result.pending()).isEmpty();
        verify(changeMapper, never()).insert(any(SupplierChange.class));
        // 普通字段一次 + 信用代码一次（条件带 credit_code IS NULL）
        verify(supplierMapper, times(2)).update(any(Supplier.class), any());
    }

    /** 规则：详情里账号遮成前 4 后 4，开户行、户名不遮 */
    @Test
    void detail_masksAccount() {
        SupplierFixtures.loginAs(7, "bank");
        Supplier s = SupplierFixtures.supplier(1, ACTIVE);
        when(supplierMapper.selectById(1L)).thenReturn(s);
        when(userService.nicknames(any())).thenReturn(Map.of());

        SupplierVO vo = service.detail(1L);

        assertThat(vo.getBankAccount()).isEqualTo("6222****2333");
        assertThat(vo.getBankName()).isEqualTo(s.getBankName());
        assertThat(vo.getBankAccountName()).isEqualTo(s.getBankAccountName());
    }

    /** 规则：待准入的能打样、录价格，不能下大货单（PRD §6.8.2 的表） */
    @Test
    void checkUsable_pendingAdmit_sampleOkBulkRejected() {
        when(supplierMapper.selectById(1L)).thenReturn(SupplierFixtures.supplier(1, PENDING_ADMIT));

        assertThatCode(() -> service.checkUsable(1L, Usage.SAMPLE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.checkUsable(1L, Usage.BULK))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("「待准入」");
    }

    /** 规则：黑名单里的不能删（信用代码要一直占着），什么都不删 */
    @Test
    void delete_blacklisted_rejected() {
        when(supplierMapper.selectByIdForUpdate(1L)).thenReturn(SupplierFixtures.supplier(1, BLACKLISTED));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("黑名单里的不能删");
        verify(supplierMapper, never()).deleteById(anyLong());
        verify(supplierMapper, never()).deleteCategories(anyLong());
    }

    private void stubLoaded(Supplier current) {
        when(supplierMapper.selectByIdForUpdate(current.getId())).thenReturn(current);
        when(supplierMapper.selectCategories(current.getId())).thenReturn(List.of("FABRIC"));
        when(supplierMapper.selectGoodAtOf(List.of(current.getId()))).thenReturn(List.of());
    }
}
