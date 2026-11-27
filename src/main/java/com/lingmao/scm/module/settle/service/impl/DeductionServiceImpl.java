package com.lingmao.scm.module.settle.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.settle.dto.DeductionQuery;
import com.lingmao.scm.module.settle.dto.DeductionReq;
import com.lingmao.scm.module.settle.dto.DeductionRowVO;
import com.lingmao.scm.module.settle.entity.Deduction;
import com.lingmao.scm.module.settle.entity.Statement;
import com.lingmao.scm.module.settle.mapper.DeductionMapper;
import com.lingmao.scm.module.settle.mapper.StatementItemMapper;
import com.lingmao.scm.module.settle.mapper.StatementMapper;
import com.lingmao.scm.module.settle.service.DeductionService;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LineScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 扣款登记（RFC 019 §5.2）。进了没作废的对账单的不能改、不能删：先锁住这一条再查（生成对账单拉扣款时 FOR UPDATE，两边排队）
 */
@Service
@RequiredArgsConstructor
public class DeductionServiceImpl implements DeductionService {

    /** stl_deduction.source_type：质检记录登记的 */
    static final String SOURCE_QC = "QC_INSPECTION";

    private final DeductionMapper deductionMapper;
    private final StatementItemMapper itemMapper;
    private final StatementMapper statementMapper;
    private final CompanyService companyService;
    private final SupplierService supplierService;
    private final UserService userService;
    private final Clock clock;

    @Override
    public PageResult<DeductionRowVO> page(DeductionQuery query) {
        IPage<DeductionRowVO> page = deductionMapper.selectRowPage(query.toPage(), query, DataScope.current(false));
        Map<Long, String> names = userService.nicknames(page.getRecords().stream().map(DeductionRowVO::getCreateBy)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        page.getRecords().forEach(r -> r.setCreatorName(names.get(r.getCreateBy())));
        return PageResult.of(page);
    }

    @Override
    @Transactional
    public Long create(DeductionReq req) {
        CompanyContext.create(req.companyId());
        Deduction d = new Deduction();
        fill(d, req);
        deductionMapper.insert(d);
        return d.getId();
    }

    @Override
    @Transactional
    public int update(long id, DeductionReq req) {
        Deduction d = lockFree(id, "改");
        if (req.version() == null || !req.version().equals(d.getVersion())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这条扣款刚被别人改过，请刷新后再试");
        }
        if (!d.getCompanyId().equals(req.companyId())) {
            throw new BizException(400, "扣款登记了就不能换主体：删了重新登记");
        }
        Deduction changed = new Deduction();
        fill(changed, req);
        deductionMapper.update(changed, new LambdaUpdateWrapper<Deduction>()
                .eq(Deduction::getId, id)
                .set(Deduction::getRefNo, changed.getRefNo())
                .setSql("version = version + 1"));
        return d.getVersion() + 1;
    }

    @Override
    @Transactional
    public void delete(long id) {
        lockFree(id, "删");
        deductionMapper.deleteById(id);
    }

    /** 锁住，查范围；别的单据登记的、进了没作废的对账单的报 1000（说是哪张、怎么办） */
    private Deduction lockFree(long id, String what) {
        Deduction d = deductionMapper.lockById(id);
        if (d == null) {
            throw new BizException(404, "这条扣款不存在");
        }
        DataScope.current(false).check(d.getCreateBy());
        checkPartnerVisible(d.getPartnerId(), "这条扣款不存在");
        CompanyContext.target(d.getCompanyId());
        if (SOURCE_QC.equals(d.getSourceType())) {
            throw new BizException("这条扣款是质检记录 " + d.getRefNo() + " 登记的，到 生产 › 质检 的这条记录上" + what);
        }
        checkNotInStatement(id, what);
        return d;
    }

    private void checkNotInStatement(long id, String what) {
        Long statementId = itemMapper.statementOfDeduction(id);
        if (statementId != null) {
            Statement s = statementMapper.selectById(statementId);
            throw new BizException("这条扣款已经进了对账单 " + s.getNo() + "，不能" + what
                    + "：对账单还是草稿的，在对账单上移出这一行或者作废对账单以后再" + what);
        }
    }

    // ---------------------------------------------------------------- 质检记录登记的（RFC 019 §6.2）

    @Override
    @Transactional
    public long createSourced(Sourced src) {
        Deduction d = new Deduction();
        d.setCompanyId(src.companyId());
        d.setPartnerId(src.partnerId());
        d.setBizDate(src.bizDate());
        d.setAmount(src.amount());
        d.setReason(reason(src.reason()));
        d.setRefNo(src.refNo());
        d.setSourceType(src.sourceType());
        d.setSourceId(src.sourceId());
        deductionMapper.insert(d);
        return d.getId();
    }

    @Override
    @Transactional
    public void updateSourced(long id, Sourced src) {
        Deduction d = lockSourced(id, src);
        boolean money = d.getAmount().compareTo(src.amount()) != 0 || !d.getBizDate().equals(src.bizDate());
        String reason = reason(src.reason());
        if (!money && reason.equals(d.getReason())) {
            return;
        }
        if (itemMapper.statementOfDeduction(id) != null) {
            if (money) {
                checkNotInStatement(id, "改金额、日期");
            }
            return;   // 只改了原因：对账单上已经抄过去了，不动
        }
        deductionMapper.update(null, new LambdaUpdateWrapper<Deduction>()
                .eq(Deduction::getId, id)
                .set(Deduction::getAmount, src.amount())
                .set(Deduction::getBizDate, src.bizDate())
                .set(Deduction::getReason, reason)
                .setSql("version = version + 1"));
    }

    @Override
    @Transactional
    public void deleteSourced(long id) {
        Deduction d = deductionMapper.lockById(id);
        if (d == null) {
            return;
        }
        checkNotInStatement(id, "删");
        deductionMapper.deleteById(d.getId());
    }

    private Deduction lockSourced(long id, Sourced src) {
        Deduction d = deductionMapper.lockById(id);
        if (d == null || !src.sourceType().equals(d.getSourceType()) || d.getSourceId() != src.sourceId()) {
            throw new BizException(404, "这条扣款不存在");
        }
        return d;
    }

    /** 原因最多 500 字（stl_deduction.reason） */
    private static String reason(String text) {
        String s = text.strip();
        return s.length() > 500 ? s.substring(0, 499) + "…" : s;
    }

    /** 品类线（RFC 013 §2）：和供应商下拉一样，这家做的线里有我能看的才行，不然当不存在 */
    private void checkPartnerVisible(long partnerId, String notFound) {
        if (!LineScope.current().canSeeAny(supplierService.productLines(partnerId))) {
            throw new BizException(404, notFound);
        }
    }

    private void fill(Deduction d, DeductionReq req) {
        if (req.bizDate().isAfter(LocalDate.now(clock))) {
            throw new BizException(400, "扣款日期不能晚于今天");
        }
        companyService.getById(req.companyId());
        supplierService.getRef(req.partnerId());
        checkPartnerVisible(req.partnerId(), "供应商不存在");
        d.setCompanyId(req.companyId());
        d.setPartnerId(req.partnerId());
        d.setBizDate(req.bizDate());
        d.setAmount(req.amount());
        d.setReason(req.reason().strip());
        d.setRefNo(req.refNo() == null || req.refNo().isBlank() ? null : req.refNo().strip());
    }
}
