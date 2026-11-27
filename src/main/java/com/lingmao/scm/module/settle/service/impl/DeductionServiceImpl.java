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

    /** 锁住，查范围，进了没作废的对账单的报 1000（说是哪张、怎么办） */
    private Deduction lockFree(long id, String what) {
        Deduction d = deductionMapper.lockById(id);
        if (d == null) {
            throw new BizException(404, "这条扣款不存在");
        }
        DataScope.current(false).check(d.getCreateBy());
        CompanyContext.target(d.getCompanyId());
        Long statementId = itemMapper.statementOfDeduction(id);
        if (statementId != null) {
            Statement s = statementMapper.selectById(statementId);
            throw new BizException("这条扣款已经进了对账单 " + s.getNo() + "，不能" + what
                    + "：对账单还是草稿的，在对账单上移出这一行或者作废对账单以后再" + what);
        }
        return d;
    }

    private void fill(Deduction d, DeductionReq req) {
        if (req.bizDate().isAfter(LocalDate.now(clock))) {
            throw new BizException(400, "扣款日期不能晚于今天");
        }
        companyService.getById(req.companyId());
        supplierService.getRef(req.partnerId());
        d.setCompanyId(req.companyId());
        d.setPartnerId(req.partnerId());
        d.setBizDate(req.bizDate());
        d.setAmount(req.amount());
        d.setReason(req.reason().strip());
        d.setRefNo(req.refNo() == null || req.refNo().isBlank() ? null : req.refNo().strip());
    }
}
