package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.production.dto.NodePlanReq;
import com.lingmao.scm.module.production.dto.NodeReportReq;
import com.lingmao.scm.module.production.dto.NodeReportResult;
import com.lingmao.scm.module.production.dto.NodeReportVO;
import com.lingmao.scm.module.production.entity.NodeReport;
import com.lingmao.scm.module.production.entity.NodeReportAction;
import com.lingmao.scm.module.production.entity.ProductionNode;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.mapper.NodeReportMapper;
import com.lingmao.scm.module.production.mapper.ProductionNodeMapper;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.production.service.ProductionNodeService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductionNodeServiceImpl implements ProductionNodeService {

    private final ProductionNodeMapper nodeMapper;
    private final ProductionOrderMapper prdMapper;
    private final NodeReportMapper reportMapper;
    private final ProductionSupport support;
    private final AttachmentService attachmentService;
    private final DocLogService docLogService;
    private final UserService userService;
    private final Clock clock;

    /**
     * 改节点计划（§3.3.2）：独立计划版本防双设备覆盖，报进度不影响该版本。
     */
    @Override
    @Transactional
    public int updatePlan(long prdOrderId, NodePlanReq req) {
        ProductionOrder p = support.lock(prdOrderId);
        if (req.getPlanRevision() == null) throw new BizException(400, "缺少 planRevision，请刷新节点计划");
        if (!Objects.equals(p.getPlanRevision(), req.getPlanRevision())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "节点计划已被修改，请刷新后再改；本次计划未覆盖");
        }
        if (!ProductionRules.LIVE.contains(p.getStatus())) {
            throw new BizException("审核下单以后、完成之前才能改节点计划");
        }
        List<ProductionNode> nodes = nodeMapper.selectByOrder(prdOrderId);
        Map<Long, NodePlanReq.Item> byId = req.getNodes().stream()
                .collect(Collectors.toMap(NodePlanReq.Item::getNodeId, Function.identity(), (a, b) -> a));
        if (byId.size() != req.getNodes().size() || !byId.keySet().equals(nodes.stream().map(ProductionNode::getId)
                .collect(Collectors.toSet()))) {
            throw new BizException(400, "节点对不上，请刷新后再改");
        }
        List<String> changed = new ArrayList<>();
        for (ProductionNode n : nodes) {
            NodePlanReq.Item i = byId.get(n.getId());
            if (i.getPlanStart() == null || i.getPlanFinish() == null) {
                throw new BizException(400, n.getNodeName() + "：计划开始、计划完成都要填");
            }
            if (i.getPlanFinish().isBefore(i.getPlanStart())) {
                throw new BizException(400, n.getNodeName() + "：计划完成不能早于计划开始");
            }
            if (i.getPlanStart().equals(n.getPlanStart()) && i.getPlanFinish().equals(n.getPlanFinish())) {
                continue;
            }
            changed.add(n.getNodeName() + " " + range(n.getPlanStart(), n.getPlanFinish()) + " → " + range(i.getPlanStart(), i.getPlanFinish()));
            ProductionNode u = new ProductionNode();
            u.setId(n.getId());
            u.setPlanStart(i.getPlanStart());
            u.setPlanFinish(i.getPlanFinish());
            nodeMapper.updateById(u);
        }
        if (!changed.isEmpty()) {
            if (prdMapper.advancePlan(prdOrderId, req.getPlanRevision()) != 1) {
                throw new BizException(ErrorCodes.STATE_CHANGED, "节点计划已被修改，请刷新后再改");
            }
            docLogService.add(BizType.PRODUCTION_ORDER, prdOrderId, "update-plan", String.join("；", changed));
        }
        return req.getPlanRevision() + (changed.isEmpty() ? 0 : 1);
    }

    private static String range(LocalDate from, LocalDate to) {
        return ProdTexts.md(from) + "–" + ProdTexts.md(to);
    }

    /**
     * 报进度（§3.3.3，电脑、手机共用）：写节点的最新值，插一条报工（照片挂在它上面）；已下单的报了实际开始变生产中。
     * 报完成时前面还有节点没报完成，照样报上，返回提示
     */
    @Override
    @Transactional
    public NodeReportResult report(long prdOrderId, long nodeId, NodeReportReq req) {
        ProductionOrder p = support.lock(prdOrderId);
        if (!ProductionRules.LIVE.contains(p.getStatus())) {
            throw new BizException(p.getKind().getLabel() + "审核后、完成之前才能报进度");
        }
        ProductionNode n = node(prdOrderId, nodeId);
        LocalDate today = support.today();
        LocalDate start;
        LocalDate finish;
        if (req.getAction() == NodeReportAction.START) {
            if (req.getActualStart() == null) {
                throw new BizException(400, "实际开始要填");
            }
            if (req.getActualFinish() != null) {
                throw new BizException(400, "只报开始就不填实际完成；做完了点「报完成」");
            }
            if (n.getActualFinish() != null) {
                throw new BizException(n.getNodeName() + "已经报过完成（" + ProdTexts.md(n.getActualFinish())
                        + "），不能改回进行中；完成日期报错了，重新点「报完成」填对的日期");
            }
            start = req.getActualStart();
            finish = null;
        } else {
            finish = req.getActualFinish() != null ? req.getActualFinish() : today;
            start = req.getActualStart() != null ? req.getActualStart() : n.getActualStart() != null ? n.getActualStart() : finish;
        }
        if (finish != null && finish.isBefore(start)) {
            throw new BizException(400, "实际完成不能早于实际开始");
        }
        if (start.isAfter(today) || (finish != null && finish.isAfter(today))) {
            throw new BizException(400, "实际日期不能晚于今天");
        }
        if (req.getPhotos().size() > 9) {
            throw new BizException(400, "照片最多 9 张");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        ProductionNode u = new ProductionNode();
        u.setId(n.getId());
        u.setActualStart(start);
        u.setActualFinish(finish);
        u.setDoneQty(req.getDoneQty());
        u.setRemark(ProdTexts.blankToNull(req.getRemark()));
        u.setLastReportBy(LoginUser.currentUserId());
        u.setLastReportTime(now);
        nodeMapper.updateById(u);   // 没填的数量、备注保留节点原来的（updateById 跳过 null）

        NodeReport r = new NodeReport();
        r.setNodeId(n.getId());
        r.setPrdOrderId(prdOrderId);
        r.setAction(req.getAction());
        r.setActualStart(start);
        r.setActualFinish(finish);
        r.setDoneQty(req.getDoneQty());
        r.setRemark(ProdTexts.blankToNull(req.getRemark()));
        reportMapper.insert(r);
        if (!req.getPhotos().isEmpty()) {
            attachmentService.replace(BizType.PRODUCTION_NODE_REPORT, r.getId(), Attachment.IMAGE, req.getPhotos());
        }

        String text = n.getNodeName() + (req.getAction() == NodeReportAction.START ? "开始" : "完成")
                + (req.getDoneQty() == null ? "" : " " + req.getDoneQty() + " 件")
                + (req.getPhotos().isEmpty() ? "" : "，照片 " + req.getPhotos().size() + " 张");
        DocStatus to = support.refreshStatus(p, "report", text);

        String warning = null;
        if (req.getAction() == NodeReportAction.FINISH && nodeMapper.selectByOrder(prdOrderId).stream()
                .anyMatch(x -> x.getSeq() < n.getSeq() && x.getActualFinish() == null)) {
            warning = "前面还有节点没报完成";
        }
        return new NodeReportResult(warning, to.name());
    }

    private ProductionNode node(long prdOrderId, long nodeId) {
        ProductionNode n = nodeMapper.selectById(nodeId);
        if (n == null || n.getPrdOrderId() != prdOrderId) {
            throw new BizException(404, "节点不存在");
        }
        return n;
    }

    @Override
    public List<NodeReportVO> reports(long prdOrderId, long nodeId) {
        support.getVisible(prdOrderId);
        node(prdOrderId, nodeId);
        List<NodeReport> reports = reportMapper.selectByNode(nodeId);
        if (reports.isEmpty()) {
            return List.of();
        }
        Map<Long, List<AttachmentVO>> photos = attachmentService.lists(BizType.PRODUCTION_NODE_REPORT,
                reports.stream().map(NodeReport::getId).toList(), Attachment.IMAGE);
        Set<Long> userIds = reports.stream().map(NodeReport::getCreateBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = userService.nicknames(userIds);
        return reports.stream().map(r -> new NodeReportVO(r.getId(), r.getAction().name(), r.getActualStart(), r.getActualFinish(),
                r.getDoneQty(), r.getRemark(), photos.getOrDefault(r.getId(), List.of()), names.get(r.getCreateBy()), r.getCreateTime()))
                .toList();
    }
}
