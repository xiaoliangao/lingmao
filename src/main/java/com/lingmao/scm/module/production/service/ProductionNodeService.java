package com.lingmao.scm.module.production.service;

import com.lingmao.scm.module.production.dto.NodePlanReq;
import com.lingmao.scm.module.production.dto.NodeReportReq;
import com.lingmao.scm.module.production.dto.NodeReportResult;
import com.lingmao.scm.module.production.dto.NodeReportVO;

import java.util.List;

/** 进度节点（docs/api/05 §3.3）：改计划、报进度（电脑、手机共用）、汇报记录 */
public interface ProductionNodeService {

    void updatePlan(long prdOrderId, NodePlanReq req);

    NodeReportResult report(long prdOrderId, long nodeId, NodeReportReq req);

    List<NodeReportVO> reports(long prdOrderId, long nodeId);
}
