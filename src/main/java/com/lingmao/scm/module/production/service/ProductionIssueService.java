package com.lingmao.scm.module.production.service;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.production.dto.IssueCloseReq;
import com.lingmao.scm.module.production.dto.IssueCreated;
import com.lingmao.scm.module.production.dto.IssueReq;
import com.lingmao.scm.module.production.dto.OpenIssueVO;
import com.lingmao.scm.module.production.dto.ProductionIssueVO;

import java.util.List;

/** 生产异常（docs/api/05 §3.5）：不是单据，没有审核、作废；开始处理、关闭不管生产单什么状态都能做 */
public interface ProductionIssueService {

    List<ProductionIssueVO> list(long prdOrderId);

    /**
     * 没关闭的异常（首页"异常待处理"，docs/api/06 §2.3）：已下单、生产中的生产单上的，按数据范围、品类线；
     * merchId 不为 null 时只要这个人跟的。旧的在前，total 是一共几条
     */
    PageResult<OpenIssueVO> openIssues(Long merchId, PageQuery page);

    IssueCreated report(long prdOrderId, IssueReq req);

    void start(long prdOrderId, long issueId);

    void close(long prdOrderId, long issueId, IssueCloseReq req);
}
