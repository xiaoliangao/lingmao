package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.module.basic.dto.CompanyOption;
import com.lingmao.scm.module.basic.dto.CompanySaveReq;
import com.lingmao.scm.module.basic.entity.Company;
import com.lingmao.scm.module.basic.mapper.CompanyMapper;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.system.dto.CompanyBrief;
import com.lingmao.scm.module.system.service.DefaultCompanyProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CompanyServiceImpl implements CompanyService, DefaultCompanyProvider {

    private final CompanyMapper companyMapper;

    @Override
    public List<Company> list() {
        return companyMapper.selectList(new LambdaQueryWrapper<Company>().orderByAsc(Company::getCode));
    }

    @Override
    public Company getById(long id) {
        Company company = companyMapper.selectById(id);
        if (company == null) {
            throw new BizException(404, "主体公司不存在: " + id);
        }
        return company;
    }

    @Override
    public List<CompanyOption> options() {
        return companyMapper.selectList(new LambdaQueryWrapper<Company>()
                        .eq(Company::getStatus, Statuses.ENABLED)
                        .orderByAsc(Company::getCode))
                .stream().map(CompanyOption::from).toList();
    }

    /** 默认主体（RFC 003 §3.1）：用户设的启用就用它；没设或停用了，启用的只有一个就用那个；都不是返回 null，单据上自己选 */
    @Override
    public CompanyBrief resolve(Long preferredId) {
        if (preferredId != null) {
            Company preferred = companyMapper.selectById(preferredId);
            if (preferred != null && preferred.getStatus() == Statuses.ENABLED) {
                return brief(preferred);
            }
        }
        // 只取两行就够判断"是不是只有一个"
        List<Company> enabled = companyMapper.selectList(new LambdaQueryWrapper<Company>()
                .eq(Company::getStatus, Statuses.ENABLED)
                .orderByAsc(Company::getCode)
                .last("LIMIT 2"));
        return enabled.size() == 1 ? brief(enabled.getFirst()) : null;
    }

    @Override
    public Long findEnabledId(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip();
        List<Company> found = companyMapper.selectList(new LambdaQueryWrapper<Company>()
                .eq(Company::getStatus, Statuses.ENABLED)
                .and(w -> w.eq(Company::getCode, Texts.upper(t)).or().eq(Company::getShortName, t).or().eq(Company::getName, t))
                .last("LIMIT 2"));
        return found.size() == 1 ? found.getFirst().getId() : null;
    }

    @Override
    @Transactional
    public Long create(CompanySaveReq req) {
        String code = Texts.upper(req.getCode());
        if (companyMapper.exists(new LambdaQueryWrapper<Company>().eq(Company::getCode, code))) {
            throw new BizException("编码 " + code + " 已经有了");
        }
        Company company = new Company();
        company.setCode(code);
        fill(company, req);
        companyMapper.insert(company);   // 同时建同一个编码的，唯一索引拦住后一个（1000）
        return company.getId();
    }

    @Override
    @Transactional
    public void update(long id, CompanySaveReq req) {
        Company company = getById(id);
        if (!company.getCode().equals(Texts.upper(req.getCode()))) {
            throw new BizException("编码建好就不能改：编码印在单据和打印件上。录错了而且没用过的，删掉重建");
        }
        fill(company, req);
        companyMapper.update(company, Updates.replaceById(company));
    }

    @Override
    @Transactional
    public void delete(long id) {
        getById(id);
        ensureNotLastEnabled(id, "删除");
        companyMapper.deleteById(id);   // 被单据、用户的默认主体引用了，外键拦住 → 1005
    }

    @Override
    @Transactional
    public void enable(long id) {
        Statuses.set(companyMapper, Company::new, id, Statuses.ENABLED, "主体公司");
    }

    @Override
    @Transactional
    public void disable(long id) {
        getById(id);
        ensureNotLastEnabled(id, "停用");
        Statuses.set(companyMapper, Company::new, id, Statuses.DISABLED, "主体公司");
    }

    /**
     * 至少留一个启用的（RFC 003 §3.1）：先锁住全部启用的行再数。是不是启用的也按锁住以后的结果算，
     * 不按前面读到的：两个人同时停用最后两个，后一个等前一个提交，再数时只剩它自己，被拦住
     */
    private void ensureNotLastEnabled(long id, String action) {
        List<Long> enabled = companyMapper.lockEnabledIds();
        if (enabled.contains(id) && enabled.size() <= 1) {
            throw new BizException("这是最后一个启用的主体公司，不能" + action + "：单据上必须有主体。先建好或启用另一个");
        }
    }

    /** 页面上显示简称，和下拉（CompanyOption）一致 */
    private static CompanyBrief brief(Company company) {
        return new CompanyBrief(company.getId(), company.getShortName());
    }

    private static void fill(Company company, CompanySaveReq req) {
        company.setName(Texts.trim(req.getName()));
        company.setShortName(Texts.trim(req.getShortName()));
        company.setCreditCode(Texts.upper(req.getCreditCode()));
        company.setAddress(Texts.trim(req.getAddress()));
        company.setPhone(Texts.trim(req.getPhone()));
    }
}
