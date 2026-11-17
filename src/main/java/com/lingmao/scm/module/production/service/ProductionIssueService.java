package com.lingmao.scm.module.production.service;

import com.lingmao.scm.module.production.dto.IssueCloseReq;
import com.lingmao.scm.module.production.dto.IssueCreated;
import com.lingmao.scm.module.production.dto.IssueReq;
import com.lingmao.scm.module.production.dto.ProductionIssueVO;

import java.util.List;

/** 生产异常（docs/api/05 §3.5）：不是单据，没有审核、作废；开始处理、关闭不管生产单什么状态都能做 */
public interface ProductionIssueService {

    List<ProductionIssueVO> list(long prdOrderId);

    IssueCreated report(long prdOrderId, IssueReq req);

    void start(long prdOrderId, long issueId);

    void close(long prdOrderId, long issueId, IssueCloseReq req);
}
