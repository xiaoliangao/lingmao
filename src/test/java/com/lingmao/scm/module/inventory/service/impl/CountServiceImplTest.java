package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.basic.service.MaterialCategoryService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.inventory.dto.CountCreateReq;
import com.lingmao.scm.module.inventory.dto.CountOpenWarehouseVO;
import com.lingmao.scm.module.inventory.entity.CountPurpose;
import com.lingmao.scm.module.inventory.entity.CountScope;
import com.lingmao.scm.module.inventory.entity.InvCount;
import com.lingmao.scm.module.inventory.entity.InvCountItem;
import com.lingmao.scm.module.inventory.entity.StockChangeType;
import com.lingmao.scm.module.inventory.entity.Stock;
import com.lingmao.scm.module.inventory.mapper.InvCountCategoryMapper;
import com.lingmao.scm.module.inventory.mapper.InvCountItemMapper;
import com.lingmao.scm.module.inventory.mapper.InvCountMapper;
import com.lingmao.scm.module.inventory.mapper.StockLogMapper;
import com.lingmao.scm.module.inventory.mapper.StockMapper;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 物料盘点单（docs/api/04 §3.5、PRD §6.6.8） */
@ExtendWith(MockitoExtension.class)
class CountServiceImplTest {

    @Mock
    private InvCountMapper countMapper;
    @Mock
    private InvCountItemMapper itemMapper;
    @Mock
    private InvCountCategoryMapper categoryMapper;
    @Mock
    private StockMapper stockMapper;
    @Mock
    private StockLogMapper stockLogMapper;
    @Mock
    private StockLedger ledger;
    @Mock
    private CountExcel excel;
    @Mock
    private MaterialService materialService;
    @Mock
    private MaterialCategoryService materialCategoryService;
    @Mock
    private WarehouseService warehouseService;
    @Mock
    private CompanyService companyService;
    @Mock
    private UserService userService;
    @Mock
    private DocHeaderService docHeaderService;
    @Mock
    private DocLogService docLogService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private SerialNoGenerator serialNoGenerator;

    private CountServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CountServiceImpl(countMapper, itemMapper, categoryMapper, stockMapper, stockLogMapper, ledger, excel,
                materialService, materialCategoryService, warehouseService, companyService, userService, docHeaderService,
                docLogService, attachmentService, serialNoGenerator, Clock.systemDefaultZone());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti"), null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则（PRD §6.6.8、§10）：期初录入只能手选物料（发全部、按分类 400），什么都不查不建 */
    @Test
    void create_openingMustBePicked() {
        CountCreateReq req = new CountCreateReq();
        req.setPurpose(CountPurpose.OPENING);
        req.setScope(CountScope.ALL);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(400))
                .hasMessage("期初录入只能手选物料");
        verifyNoInteractions(countMapper, stockMapper);
    }

    /** 规则（docs/api/04 §3.5.8）：提交时实盘数都要填，没填的有几行就说几行 */
    @Test
    void submit_needsEveryRowCounted() {
        InvCount count = count(DocStatus.DRAFT, CountPurpose.DAILY);
        when(countMapper.selectById(7L)).thenReturn(count);
        when(countMapper.lockById(7L)).thenReturn(count);
        when(itemMapper.selectByCount(7L)).thenReturn(List.of(item(1, "5", "5"), item(2, "3", null), item(3, "0", null)));

        assertThatThrownBy(() -> service.act(7L, "submit", new DocActionReq(0, null)))
                .isInstanceOf(BizException.class).hasMessage("还有 2 行没填实盘数");
        verify(countMapper, never()).update(any(), any());
    }

    /** 数量恢复原值仍然发生过收发，必须重取并重数，不能只比较余额。 */
    @Test
    void submit_rejectsMovementRevisionEvenWhenQtySame() {
        InvCount c = count(DocStatus.DRAFT, CountPurpose.DAILY);
        InvCountItem i = item(1, "5", "5"); i.setBookRevision(10L);
        Stock now = new Stock(); now.setQty(new BigDecimal("5")); now.setMovementRevision(12L);
        when(countMapper.selectById(7L)).thenReturn(c);
        when(countMapper.lockById(7L)).thenReturn(c);
        when(itemMapper.selectByCount(7L)).thenReturn(List.of(i));
        when(ledger.lockBalances(3L, List.of(11L))).thenReturn(Map.of(11L, now));
        assertThatThrownBy(() -> service.act(7L, "submit", new DocActionReq(0, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("重数").extracting("code").isEqualTo(1002);
        verify(countMapper, never()).update(any(), any());
    }

    /**
     * 规则（PRD §6.6.8 规则 2）：调整量 = 实盘 − 账面（快照），差异是 0 的行不动库存；日常盘点记盘盈、盘亏，期初录入记期初
     */
    @Test
    void posting_adjustsByDifference() {
        List<InvCountItem> items = List.of(item(1, "5", "7.5"), item(2, "3", "3"), item(3, "10", "4"));

        assertThat(CountServiceImpl.posting(count(DocStatus.PENDING, CountPurpose.DAILY), items).moves())
                .extracting(StockLedger.Move::seq, StockLedger.Move::delta, StockLedger.Move::changeType, StockLedger.Move::countItemId)
                .containsExactly(tuple(1, new BigDecimal("2.5"), StockChangeType.COUNT_GAIN, 101L),
                        tuple(3, new BigDecimal("-6"), StockChangeType.COUNT_LOSS, 103L));
        assertThat(CountServiceImpl.posting(count(DocStatus.PENDING, CountPurpose.OPENING), List.of(item(1, "0", "12"))).moves())
                .extracting(StockLedger.Move::changeType).containsExactly(StockChangeType.OPENING);
    }

    /** 规则（PRD §6.6.8 规则 4）：反审核会回到草稿，这个仓已经开了另一张没审核的盘点单就不行（1000，说出是哪张），库存不动 */
    @Test
    void unaudit_blockedByAnotherOpenCount() {
        when(countMapper.selectById(7L)).thenReturn(count(DocStatus.APPROVED, CountPurpose.DAILY));
        when(countMapper.selectOpen()).thenReturn(List.of(new CountOpenWarehouseVO(3L, 8L, "PD202611170002", "DRAFT")));

        assertThatThrownBy(() -> service.act(7L, "unaudit", new DocActionReq(2, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("PD202611170002");
        verifyNoInteractions(ledger);
    }

    private static InvCount count(DocStatus status, CountPurpose purpose) {
        InvCount c = new InvCount();
        c.setId(7L);
        c.setNo("PD202611170001");
        c.setWarehouseId(3L);
        c.setPurpose(purpose);
        c.setScope(CountScope.PICKED);
        c.setStatus(status);
        c.setVersion(0);
        c.setCountDate(LocalDate.of(2026, 11, 17));
        c.setCreateBy(1L);
        c.setCounterId(1L);
        return c;
    }

    private static InvCountItem item(int seq, String book, String counted) {
        InvCountItem i = new InvCountItem();
        i.setId(100L + seq);
        i.setSeq(seq);
        i.setMaterialSkuId(10L + seq);
        i.setBookQty(new BigDecimal(book));
        i.setCountedQty(counted == null ? null : new BigDecimal(counted));
        return i;
    }
}
