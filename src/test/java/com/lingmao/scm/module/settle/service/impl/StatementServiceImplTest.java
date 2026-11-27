package com.lingmao.scm.module.settle.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.settle.dto.CompareResult;
import com.lingmao.scm.module.settle.dto.StatementCreateReq;
import com.lingmao.scm.module.settle.entity.Statement;
import com.lingmao.scm.module.settle.entity.StatementItem;
import com.lingmao.scm.module.settle.entity.StatementSourceType;
import com.lingmao.scm.module.settle.entity.StatementStatus;
import com.lingmao.scm.module.settle.mapper.DeductionMapper;
import com.lingmao.scm.module.settle.mapper.StatementItemMapper;
import com.lingmao.scm.module.settle.mapper.StatementMapper;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.FileService;
import com.lingmao.scm.module.system.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 供应商对账单的规则（RFC 019 §5.1）：生成时怎么算钱、有差异没说明不能确认、作废放开单据、按钮按权限给 */
@ExtendWith(MockitoExtension.class)
class StatementServiceImplTest {

    private static final long USER = 7L;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    @Mock
    private StatementMapper statementMapper;
    @Mock
    private StatementItemMapper itemMapper;
    @Mock
    private DeductionMapper deductionMapper;
    @Mock
    private CompanyService companyService;
    @Mock
    private SupplierService supplierService;
    @Mock
    private UserService userService;
    @Mock
    private DictDataService dictDataService;
    @Mock
    private DocLogService docLogService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private FileService fileService;
    @Mock
    private SerialNoGenerator serialNoGenerator;

    private StatementServiceImpl service;

    @BeforeAll
    static void tableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Statement.class);
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-28T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new StatementServiceImpl(statementMapper, itemMapper, deductionMapper, companyService, supplierService, userService,
                dictDataService, docLogService, attachmentService, fileService, serialNoGenerator, JsonMapper.builder().build(), clock);
        TestIdentity.login(USER, List.of(StatementServiceImpl.EDIT));
    }

    @AfterEach
    void clear() {
        TestIdentity.clear();
    }

    /**
     * 入库 10 × 20 = 200；退货 2 × 20 记成 −2、−40；回货 100 件 × 38 = 3800，次品 2 件扣 76；扣款登记 100。
     * 货款 3960，扣款 176，实付 3784；明细按日期排，序号从 1 起
     */
    @Test
    void create_signsAndPricesLines() {
        when(supplierService.getRef(9L)).thenReturn(partner());
        when(serialNoGenerator.daily("DZ")).thenReturn("DZ202611280001");
        when(statementMapper.selectPurchaseLines(1L, 9L, FROM, TO)).thenReturn(List.of(
                line(StatementSourceType.PURCHASE_IN, 11, LocalDate.of(2026, 10, 5), "10", "20", "200", null),
                line(StatementSourceType.PURCHASE_RETURN, 12, LocalDate.of(2026, 10, 20), "2", "20", "40", null)));
        when(statementMapper.selectReceipts(eq(1L), eq(9L), eq(FROM), eq(TO), any())).thenReturn(List.of(
                line(StatementSourceType.PRODUCTION_RECEIPT, 21, LocalDate.of(2026, 10, 15), "100", "38", null, "2")));
        when(statementMapper.selectDeductions(1L, 9L, FROM, TO)).thenReturn(List.of(
                line(StatementSourceType.DEDUCTION, 31, LocalDate.of(2026, 10, 25), null, null, "0", "100")));
        when(statementMapper.insert(any(Statement.class))).thenAnswer(inv -> {
            inv.<Statement>getArgument(0).setId(5L);
            return 1;
        });
        ArgumentCaptor<Statement> head = ArgumentCaptor.forClass(Statement.class);
        ArgumentCaptor<StatementItem> items = ArgumentCaptor.forClass(StatementItem.class);

        assertThat(service.create(new StatementCreateReq(1L, 9L, FROM, TO, " ")).added()).isEqualTo(4);

        verify(statementMapper).insert(head.capture());
        assertThat(head.getValue().getTotalAmount()).isEqualByComparingTo("3960");
        assertThat(head.getValue().getDeductAmount()).isEqualByComparingTo("176");
        assertThat(head.getValue().getPayableAmount()).isEqualByComparingTo("3784");
        assertThat(head.getValue().getRemark()).isNull();
        verify(itemMapper, org.mockito.Mockito.times(4)).insert(items.capture());
        assertThat(items.getAllValues()).extracting(StatementItem::getSeq, StatementItem::getSourceId, i -> i.getQty() == null ? null : i.getQty().toPlainString(),
                        i -> i.getAmount().toPlainString(), i -> i.getDeductAmount().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 11L, "10", "200", "0"),
                        org.assertj.core.groups.Tuple.tuple(2, 21L, "100", "3800.00", "76.00"),
                        org.assertj.core.groups.Tuple.tuple(3, 12L, "-2", "-40", "0"),
                        org.assertj.core.groups.Tuple.tuple(4, 31L, null, "0", "100"));
        assertThat(items.getAllValues().get(1).getDescription()).endsWith("，次品 2");
        verify(docLogService).add(eq(BizType.STATEMENT), eq(5L), eq("create"), any(), eq(StatementStatus.DRAFT), eq("生成，4 行"));
    }

    /** 比对过、还有一处差异没写说明：不能确认，列出是哪一处；状态不动 */
    @Test
    void confirm_needsNotesForEveryDifference() {
        TestIdentity.login(USER, List.of(StatementServiceImpl.CONFIRM));
        CompareResult.Row same = new CompareResult.Row("1", CompareResult.Outcome.MATCH, null, null, null, BigDecimal.ZERO, null);
        CompareResult.Row noted = new CompareResult.Row("2", CompareResult.Outcome.DIFF, null, null, null, BigDecimal.TEN, "对方单价错了");
        CompareResult.Row open = new CompareResult.Row("3", CompareResult.Outcome.THEIRS_ONLY,
                new CompareResult.Their(List.of(5), "SH9", TO, "织带", null, new BigDecimal("50"), false), null, null,
                new BigDecimal("50"), " ");
        Statement s = statement(StatementStatus.SENT);
        s.setCompareResult(new CompareResult("x.xlsx", OffsetDateTime.now(), USER, "我", "S", 3, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(same, noted, open)));
        when(statementMapper.lockById(5L)).thenReturn(s);

        assertThatThrownBy(() -> service.act(5L, "confirm", new DocActionReq(3, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("还有 1 处差异没写说明")
                .satisfies(e -> assertThat(((BizException) e).getData()).isEqualTo(List.of("第 3 处 对方有我方没有：SH9，差 50 元")));
        verify(statementMapper, never()).update(any(), any());
    }

    /** 作废草稿：状态改了以后明细全部放开（单据回到待对账），写操作记录 */
    @Test
    void void_releasesSources() {
        when(statementMapper.lockById(5L)).thenReturn(statement(StatementStatus.DRAFT));
        when(statementMapper.update(any(), any())).thenReturn(1);

        service.act(5L, "void", new DocActionReq(3, "期间选错了"));

        verify(itemMapper).deactivate(5L);
        verify(docLogService).add(BizType.STATEMENT, 5L, "void", StatementStatus.DRAFT, StatementStatus.VOIDED, "期间选错了");
    }

    /** 按钮：草稿给编辑类的（空的不给发送）；已发送给撤回、比对，有确认权限才给确认；比对过才有写说明 */
    @Test
    void actions_followStatusAndPermission() {
        Statement draft = statement(StatementStatus.DRAFT);
        assertThat(StatementServiceImpl.actions(draft, false)).containsExactly("refresh", "removeItems", "addDeduction", "compare", "void");
        assertThat(StatementServiceImpl.actions(draft, true)).contains("send");
        Statement sent = statement(StatementStatus.SENT);
        assertThat(StatementServiceImpl.actions(sent, true)).containsExactly("compare", "withdraw");
        TestIdentity.login(USER, List.of(StatementServiceImpl.CONFIRM));
        assertThat(StatementServiceImpl.actions(sent, true)).containsExactly("confirm");
        assertThat(StatementServiceImpl.actions(statement(StatementStatus.CONFIRMED), true)).isEmpty();
        verify(statementMapper, never()).lockById(anyLong());
    }

    private static Statement statement(StatementStatus status) {
        Statement s = new Statement();
        s.setId(5L);
        s.setNo("DZ202611280001");
        s.setPartnerId(9L);
        s.setStatus(status);
        s.setVersion(3);
        s.setCreateBy(USER);
        s.setPeriodStart(FROM);
        s.setPeriodEnd(TO);
        return s;
    }

    private static SupplierRef partner() {
        return new SupplierRef(9L, "GYS0009", "某某制衣厂", "某某", null, null, null, null, null, null, null, null);
    }

    private static StatementItem line(StatementSourceType type, long sourceId, LocalDate date, String qty, String price, String amount,
                                      String deduct) {
        StatementItem i = new StatementItem();
        i.setSourceType(type);
        i.setSourceId(sourceId);
        i.setBizDate(date);
        i.setDescription("摘要");
        i.setQty(qty == null ? null : new BigDecimal(qty));
        i.setPrice(price == null ? null : new BigDecimal(price));
        i.setAmount(amount == null ? null : new BigDecimal(amount));
        i.setDeductAmount(deduct == null ? null : new BigDecimal(deduct));
        return i;
    }
}
