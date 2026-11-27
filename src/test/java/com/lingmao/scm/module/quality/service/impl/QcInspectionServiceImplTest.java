package com.lingmao.scm.module.quality.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.quality.dto.QcReq;
import com.lingmao.scm.module.quality.dto.QcRowVO;
import com.lingmao.scm.module.quality.dto.QcSourceVO;
import com.lingmao.scm.module.quality.entity.QcInspection;
import com.lingmao.scm.module.quality.entity.QcKind;
import com.lingmao.scm.module.quality.entity.QcResult;
import com.lingmao.scm.module.quality.mapper.QcInspectionMapper;
import com.lingmao.scm.module.settle.service.DeductionService;
import com.lingmao.scm.module.system.service.DictDataService;
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

/** 质检的规则（RFC 019 §6）：回货验货 → 返修 → 再验的次数、必填和数量、质量扣款跟着记录走、没扣款权限的不碰金额 */
@ExtendWith(MockitoExtension.class)
class QcInspectionServiceImplTest {

    private static final long USER = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
    private static final String EDIT = "quality:inspection:edit";

    @Mock
    private QcInspectionMapper qcMapper;
    @Mock
    private DeductionService deductionService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private DictDataService dictDataService;
    @Mock
    private UserService userService;
    @Mock
    private SerialNoGenerator serialNoGenerator;

    private QcInspectionServiceImpl service;

    @BeforeAll
    static void tableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QcInspection.class);
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-28T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new QcInspectionServiceImpl(qcMapper, deductionService, attachmentService, dictDataService, userService,
                serialNoGenerator, clock);
    }

    @AfterEach
    void clear() {
        TestIdentity.clear();
    }

    /**
     * 回货第 1 次不合格：没安排返修、在返修都不能再验（说先做什么）；返修完成了是第 2 次。
     * 合格、让步接收以后不能再记一次；没审核的回货不能验
     */
    @Test
    void nextFinal_reworkLoop() {
        QcSourceVO s = receipt();
        OffsetDateTime t = OffsetDateTime.parse("2026-11-20T10:00:00+08:00");

        assertThat(QcInspectionServiceImpl.nextFinal(s, null)).isEqualTo(1);
        assertThatThrownBy(() -> QcInspectionServiceImpl.nextFinal(s, new QcInspectionServiceImpl.Attempt(1, QcResult.FAIL, null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("还没安排返修");
        assertThatThrownBy(() -> QcInspectionServiceImpl.nextFinal(s, new QcInspectionServiceImpl.Attempt(1, QcResult.FAIL, t, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("在返修");
        assertThat(QcInspectionServiceImpl.nextFinal(s, new QcInspectionServiceImpl.Attempt(1, QcResult.FAIL, t, t.plusDays(1))))
                .isEqualTo(2);
        assertThatThrownBy(() -> QcInspectionServiceImpl.nextFinal(s, new QcInspectionServiceImpl.Attempt(2, QcResult.CONCESSION, null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("第 2 次验货已经让步接收了");

        s.setSourceStatus("VOIDED");
        assertThatThrownBy(() -> QcInspectionServiceImpl.nextFinal(s, null))
                .isInstanceOf(BizException.class).hasMessageContaining("已作废");
    }

    /** 必填和数量：抽检数（产前样可以不填）、不合格不大于抽检、不是合格要写问题、产前样没有让步接收、日期不晚于今天 */
    @Test
    void validate_rules() {
        TestIdentity.login(USER, List.of(EDIT));
        assertThatThrownBy(() -> service.validate(req(null, null, QcResult.PASS, null, null), QcKind.FINAL))
                .hasMessage("抽检数要填");
        assertThatThrownBy(() -> service.validate(req("5", "6", QcResult.FAIL, "做工", null), QcKind.FINAL))
                .hasMessage("不合格数不能大于抽检数");
        assertThatThrownBy(() -> service.validate(req("5", "1", QcResult.CONCESSION, " ", null), QcKind.INCOMING))
                .hasMessage("不合格、让步接收要写问题");
        assertThatThrownBy(() -> service.validate(req(null, null, QcResult.CONCESSION, "颜色偏深", null), QcKind.PRE_PRODUCTION))
                .hasMessageContaining("产前样只有");
        assertThatThrownBy(() -> service.validate(new QcReq(null, null, null, TODAY.plusDays(1), BigDecimal.TEN, null,
                QcResult.PASS, null, null, null, null), QcKind.MIDDLE)).hasMessage("检验日期不能晚于今天");

        service.validate(req(null, null, QcResult.PASS, null, null), QcKind.PRE_PRODUCTION);   // 产前样不填抽检数可以
    }

    /**
     * 返修回来第 2 次验，不合格、扣 150：记录是第 2 次，允收数按批量 200 取 AQL（2），
     * 同时登记一笔扣款（来源是这条记录，日期是检验日期，原因带单号和问题），记录上挂扣款 id
     */
    @Test
    void create_secondAttemptWithDeduction() {
        TestIdentity.login(USER, List.of(EDIT, QcInspectionServiceImpl.DEDUCT_EDIT));
        OffsetDateTime t = OffsetDateTime.parse("2026-11-20T10:00:00+08:00");
        when(qcMapper.selectReceiptSource(31L)).thenReturn(receipt());
        QcInspection first = new QcInspection();
        first.setAttemptNo(1);
        first.setResult(QcResult.FAIL);
        first.setReworkTime(t);
        first.setReworkDoneTime(t.plusDays(2));
        when(qcMapper.lockLatestFinal(31L)).thenReturn(first);
        when(serialNoGenerator.daily("ZJ")).thenReturn("ZJ202611280001");
        when(qcMapper.insert(any(QcInspection.class))).thenAnswer(inv -> {
            inv.<QcInspection>getArgument(0).setId(50L);
            return 1;
        });
        when(deductionService.createSourced(any())).thenReturn(88L);

        var created = service.create(new QcReq(null, QcKind.FINAL, 31L, TODAY, new BigDecimal("32"), new BigDecimal("4"),
                QcResult.FAIL, null, "领口不平 4 件", null, new BigDecimal("150")));

        assertThat(created.no()).isEqualTo("ZJ202611280001");
        ArgumentCaptor<QcInspection> saved = ArgumentCaptor.forClass(QcInspection.class);
        verify(qcMapper).insert(saved.capture());
        assertThat(saved.getValue().getAttemptNo()).isEqualTo(2);
        assertThat(saved.getValue().getPrdOrderId()).isEqualTo(21L);
        assertThat(saved.getValue().getPartnerId()).isEqualTo(9L);
        assertThat(saved.getValue().getAcceptQty()).isEqualByComparingTo("2");
        ArgumentCaptor<DeductionService.Sourced> deduction = ArgumentCaptor.forClass(DeductionService.Sourced.class);
        verify(deductionService).createSourced(deduction.capture());
        assertThat(deduction.getValue()).isEqualTo(new DeductionService.Sourced("QC_INSPECTION", 50L, 1L, 9L, TODAY,
                new BigDecimal("150"), "质检 ZJ202611280001：尾期验货不合格，领口不平 4 件", "ZJ202611280001"));
        verify(qcMapper).update(any(), any());   // 挂上 deduction_id
    }

    /**
     * 没有扣款权限：记的时候填了金额报 403、什么都不写；改一条有扣款的记录，请求里的金额不看，
     * 扣款按原来的金额只同步日期、原因；安排了返修的结论不能改
     */
    @Test
    void deduction_needsPermission_andReworkLocksResult() {
        TestIdentity.login(USER, List.of(EDIT));
        when(qcMapper.selectReceiptSource(31L)).thenReturn(receipt());
        assertThatThrownBy(() -> service.create(new QcReq(null, QcKind.FINAL, 31L, TODAY, BigDecimal.TEN, BigDecimal.ONE,
                QcResult.FAIL, null, "污渍", null, new BigDecimal("50"))))
                .isInstanceOf(BizException.class).hasMessageContaining("没有扣款登记的权限");
        verify(qcMapper, never()).insert(any(QcInspection.class));

        QcInspection q = new QcInspection();
        q.setId(50L);
        q.setNo("ZJ202611200001");
        q.setKind(QcKind.FINAL);
        q.setCompanyId(1L);
        q.setPartnerId(9L);
        q.setResult(QcResult.FAIL);
        q.setVersion(3);
        q.setDeductionId(88L);
        when(qcMapper.lockById(50L)).thenReturn(q);
        when(qcMapper.update(any(), any())).thenReturn(1);
        QcRowVO row = new QcRowVO();
        row.setDeductAmount(new BigDecimal("120"));
        when(qcMapper.selectRow(50L)).thenReturn(row);

        int version = service.update(50L, new QcReq(3, null, null, TODAY.minusDays(1), BigDecimal.TEN, new BigDecimal("2"),
                QcResult.FAIL, null, "污渍 2 件", null, null));

        assertThat(version).isEqualTo(4);
        verify(deductionService).updateSourced(eq(88L), eq(new DeductionService.Sourced("QC_INSPECTION", 50L, 1L, 9L,
                TODAY.minusDays(1), new BigDecimal("120"), "质检 ZJ202611200001：尾期验货不合格，污渍 2 件", "ZJ202611200001")));
        verify(deductionService, never()).deleteSourced(anyLong());

        q.setReworkTime(OffsetDateTime.parse("2026-11-21T10:00:00+08:00"));
        q.setVersion(4);
        assertThatThrownBy(() -> service.update(50L, new QcReq(4, null, null, TODAY, BigDecimal.TEN, BigDecimal.ZERO,
                QcResult.PASS, null, null, null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("已经安排了返修，结论不能再改");
    }

    private static QcSourceVO receipt() {
        QcSourceVO s = new QcSourceVO();
        s.setSourceId(31L);
        s.setSourceNo("HH202611200001");
        s.setSourceStatus("APPROVED");
        s.setCompanyId(1L);
        s.setPartnerId(9L);
        s.setPrdOrderId(21L);
        s.setProductLine("APPAREL");
        s.setTargetDesc("FS001 卫衣");
        s.setLotQty(new BigDecimal("200"));
        s.setUnit("PIECE");
        return s;
    }

    private static QcReq req(String sample, String defect, QcResult result, String note, BigDecimal deduct) {
        return new QcReq(null, null, null, TODAY, sample == null ? null : new BigDecimal(sample),
                defect == null ? null : new BigDecimal(defect), result, null, note, null, deduct);
    }
}
