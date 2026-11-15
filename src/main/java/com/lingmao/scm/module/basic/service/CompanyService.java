package com.lingmao.scm.module.basic.service;

import com.lingmao.scm.module.basic.dto.CompanyOption;
import com.lingmao.scm.module.basic.dto.CompanySaveReq;
import com.lingmao.scm.module.basic.entity.Company;

import java.util.List;

/**
 * 主体公司（RFC 003 §3.1）。至少留一个启用的：停用、删除前锁住全部启用的行再数。
 * 给第 3 批单据用的 defaultForUser 等第一张单据开工时再加（RFC 003 §3.1）
 */
public interface CompanyService {

    /** 全部（主体就几个，不分页），按编码排 */
    List<Company> list();

    Company getById(long id);

    /** 下拉：启用的，按编码排 */
    List<CompanyOption> options();

    Long create(CompanySaveReq req);

    /** 编码建好不改：传的编码和原来不一样报 1000 */
    void update(long id, CompanySaveReq req);

    /** 没被引用的才删得掉（外键 → 1005）；删的是最后一个启用的报 1000 */
    void delete(long id);

    void enable(long id);

    /** 最后一个启用的不能停用 */
    void disable(long id);
}
