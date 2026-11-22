package com.lingmao.scm.module.production.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.order.entity.ResponsibleParty;
import com.lingmao.scm.module.production.dto.IssueCloseReq;
import com.lingmao.scm.module.production.dto.IssueCreated;
import com.lingmao.scm.module.production.dto.IssueReq;
import com.lingmao.scm.module.production.dto.OpenIssueVO;
import com.lingmao.scm.module.production.dto.ProductionIssueVO;
import com.lingmao.scm.module.production.entity.IssueStatus;
import com.lingmao.scm.module.production.entity.ProductionIssue;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.mapper.ProductionIssueMapper;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.production.service.ProductionIssueService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 生产异常（docs/api/05 §3.5）。上报只在已下单、生产中；开始处理、关闭不管生产单什么状态都能做。
 * 填了新交期就改生产单的预计交期（几条都填了以最后上报的为准），关闭不改回
 */
@Service
@RequiredArgsConstructor
public class ProductionIssueServiceImpl implements ProductionIssueService {

    private final ProductionIssueMapper issueMapper;
    private final ProductionOrderMapper prdMapper;
    private final ProductionSupport support;
    private final AttachmentService attachmentService;
    private final DocLogService docLogService;
    private final DictDataService dictDataService;
    private final UserService userService;
    private final SerialNoGenerator serialNoGenerator;
    private final TradeFeature tradeFeature;
    private final Clock clock;

    @Override
    public PageResult<OpenIssueVO> openIssues(Long merchId, PageQuery page) {
        IPage<OpenIssueVO> result = issueMapper.selectOpenPage(page.toPage(), merchId, DataScope.current(false));
        List<OpenIssueVO> rows = result.getRecords();
        if (!rows.isEmpty()) {
            Map<String, String> types = dictDataService.labels("issue_type");
            Map<Long, String> names = userService.nicknames(rows.stream().map(OpenIssueVO::getCreateBy)
                    .filter(Objects::nonNull).collect(Collectors.toSet()));
            rows.forEach(r -> {
                r.setIssueTypeName(types.getOrDefault(r.getIssueType(), r.getIssueType()));
                r.setCreateByName(names.get(r.getCreateBy()));
            });
        }
        return PageResult.of(result);
    }

    @Override
    public List<ProductionIssueVO> list(long prdOrderId) {
        support.getVisible(prdOrderId);
        List<ProductionIssue> issues = issueMapper.selectByOrder(prdOrderId);
        if (issues.isEmpty()) {
            return List.of();
        }
        List<Long> ids = issues.stream().map(ProductionIssue::getId).toList();
        Map<Long, List<AttachmentVO>> photos = attachmentService.lists(BizType.PRODUCTION_ISSUE, ids, Attachment.IMAGE);
        Set<Long> userIds = new HashSet<>();
        issues.forEach(i -> {
            userIds.add(i.getCreateBy());
            userIds.add(i.getHandlerId());
            userIds.add(i.getCloseBy());
        });
        userIds.remove(null);
        Map<Long, String> names = new HashMap<>(userService.nicknames(userIds));   // 处理人、关闭人可能是 null
        boolean canEdit = LoginUser.hasAuthority("production:issue:edit");
        List<ProductionIssueVO> out = new ArrayList<>();
        for (ProductionIssue i : issues) {
            ProductionIssueVO v = new ProductionIssueVO();
            v.setId(i.getId());
            v.setNo(i.getNo());
            v.setIssueType(i.getIssueType());
            v.setDescription(i.getDescription());
            v.setPhotos(photos.getOrDefault(i.getId(), List.of()));
            v.setImpactDelivery(Boolean.TRUE.equals(i.getImpactDelivery()));
            v.setNewExpectDate(i.getNewExpectDate());
            v.setResponsibleParty(i.getResponsibleParty());
            v.setStatus(i.getStatus().name());
            v.setCreateByName(names.get(i.getCreateBy()));
            v.setCreateTime(i.getCreateTime());
            v.setHandlerName(names.get(i.getHandlerId()));
            v.setMeasure(i.getMeasure());
            v.setCloseByName(names.get(i.getCloseBy()));
            v.setCloseTime(i.getCloseTime());
            List<String> actions = new ArrayList<>();
            if (canEdit && i.getStatus() == IssueStatus.OPEN) {
                actions.add("start");
            }
            if (canEdit && i.getStatus() != IssueStatus.CLOSED) {
                actions.add("close");
            }
            v.setActions(actions);
            out.add(v);
        }
        return out;
    }

    @Override
    @Transactional
    public IssueCreated report(long prdOrderId, IssueReq req) {
        ProductionOrder p = support.lock(prdOrderId);
        if (!ProductionRules.LIVE.contains(p.getStatus())) {
            throw new BizException(p.getKind().getLabel() + "审核后、完成之前才能上报异常");
        }
        String type = req.getIssueType().strip();
        if (dictDataService.listByType("issue_type").stream().noneMatch(d -> d.value().equals(type) && d.enabled())) {
            throw new BizException(400, "异常类型「" + type + "」不在字典里或已停用");
        }
        if (req.isImpactDelivery() && req.getNewExpectDate() == null) {
            throw new BizException(400, "影响交期要填新的预计交期");
        }
        checkParty(req.getResponsibleParty());
        ProductionIssue i = new ProductionIssue();
        i.setNo(serialNoGenerator.daily(BizType.PRODUCTION_ISSUE.prefix(null)));
        i.setPrdOrderId(prdOrderId);
        i.setIssueType(type);
        i.setDescription(req.getDescription().strip());
        i.setImpactDelivery(req.isImpactDelivery());
        i.setNewExpectDate(req.isImpactDelivery() ? req.getNewExpectDate() : null);
        i.setResponsibleParty(req.getResponsibleParty() == null ? null : req.getResponsibleParty().name());
        i.setStatus(IssueStatus.OPEN);
        issueMapper.insert(i);
        attachmentService.replace(BizType.PRODUCTION_ISSUE, i.getId(), Attachment.IMAGE, req.getPhotos());
        String text = "上报异常：" + typeLabel(type);
        if (i.getNewExpectDate() != null) {
            prdMapper.updateExpectDate(prdOrderId, i.getNewExpectDate());
            text += "，预计交期改为 " + ProdTexts.md(i.getNewExpectDate());
        }
        docLogService.add(BizType.PRODUCTION_ORDER, prdOrderId, "report-issue", text);
        return new IssueCreated(i.getId(), i.getNo());
    }

    private void checkParty(ResponsibleParty party) {
        tradeFeature.rejectIfOff(party == ResponsibleParty.CUSTOMER, "责任方「客户」");
    }

    private String typeLabel(String type) {
        return dictDataService.listByType("issue_type").stream().filter(d -> d.value().equals(type)).map(DictOption::label)
                .findFirst().orElse(type);
    }

    @Override
    @Transactional
    public void start(long prdOrderId, long issueId) {
        support.getVisible(prdOrderId);
        ProductionIssue i = lockIssue(prdOrderId, issueId);
        if (i.getStatus() != IssueStatus.OPEN) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这条异常已经有人在处理或者关了，请刷新");
        }
        issueMapper.update(new ProductionIssue(), new LambdaUpdateWrapper<ProductionIssue>()
                .set(ProductionIssue::getStatus, IssueStatus.HANDLING)
                .set(ProductionIssue::getHandlerId, LoginUser.currentUserId())
                .eq(ProductionIssue::getId, issueId));
        docLogService.add(BizType.PRODUCTION_ORDER, prdOrderId, "start-issue", i.getNo() + " " + typeLabel(i.getIssueType()));
    }

    @Override
    @Transactional
    public void close(long prdOrderId, long issueId, IssueCloseReq req) {
        support.getVisible(prdOrderId);
        ProductionIssue i = lockIssue(prdOrderId, issueId);
        if (i.getStatus() == IssueStatus.CLOSED) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这条异常已经关了，请刷新");
        }
        checkParty(req.getResponsibleParty());
        String party = req.getResponsibleParty() == null ? null : req.getResponsibleParty().name();
        issueMapper.update(new ProductionIssue(), new LambdaUpdateWrapper<ProductionIssue>()
                .set(ProductionIssue::getStatus, IssueStatus.CLOSED)
                .set(ProductionIssue::getMeasure, req.getMeasure().strip())
                .set(ProductionIssue::getResponsibleParty, party)
                .set(ProductionIssue::getCloseBy, LoginUser.currentUserId())
                .set(ProductionIssue::getCloseTime, OffsetDateTime.now(clock))
                .eq(ProductionIssue::getId, issueId));
        String text = i.getNo() + " " + typeLabel(i.getIssueType()) + "：" + req.getMeasure().strip();
        docLogService.add(BizType.PRODUCTION_ORDER, prdOrderId, "close-issue", text);
    }

    private ProductionIssue lockIssue(long prdOrderId, long issueId) {
        ProductionIssue i = issueMapper.lockById(issueId);
        if (i == null || !Objects.equals(i.getPrdOrderId(), prdOrderId)) {
            throw new BizException(404, "异常不存在");
        }
        return i;
    }
}
