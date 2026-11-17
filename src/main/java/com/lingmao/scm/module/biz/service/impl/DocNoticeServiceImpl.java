package com.lingmao.scm.module.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.dto.DocNoticeGroup;
import com.lingmao.scm.module.biz.dto.DocNoticeVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.entity.DocNotice;
import com.lingmao.scm.module.biz.mapper.DocNoticeMapper;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocNoticeServiceImpl implements DocNoticeService {

    /** content 列的长度 */
    private static final int MAX_CONTENT = 500;

    private final DocNoticeMapper noticeMapper;
    private final ReferenceService referenceService;
    private final UserService userService;
    private final Clock clock;

    @Override
    public void add(BizType docType, long docId, BizType sourceType, long sourceId, String content, Long ownerId) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("提醒要写清楚上游改了什么");
        }
        DocNotice notice = new DocNotice();
        notice.setDocType(docType);
        notice.setDocId(docId);
        notice.setSourceType(sourceType);
        notice.setSourceId(sourceId);
        // 一张变更单改了很多行时会很长：截断，详细的点来源去变更单看
        notice.setContent(content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT - 1) + "…" : content);
        notice.setOwnerId(ownerId);
        noticeMapper.insert(notice);
    }

    @Override
    public List<DocNoticeVO> list(BizType docType, long docId) {
        List<DocNotice> notices = noticeMapper.selectList(new LambdaQueryWrapper<DocNotice>()
                .eq(DocNotice::getDocType, docType)
                .eq(DocNotice::getDocId, docId));
        // 没处理的在前，各自新的在前
        List<DocNotice> sorted = notices.stream()
                .sorted(Comparator.comparing((DocNotice n) -> n.getHandledTime() != null)
                        .thenComparing(DocNotice::getId, Comparator.reverseOrder()))
                .toList();
        return toVO(sorted);
    }

    @Override
    @Transactional
    public void handle(BizType docType, long docId, long noticeId, String remark) {
        int rows = noticeMapper.update(new LambdaUpdateWrapper<DocNotice>()
                .set(DocNotice::getHandledBy, LoginUser.currentUserId())
                .set(DocNotice::getHandledTime, OffsetDateTime.now(clock))
                .set(DocNotice::getHandleRemark, StringUtils.hasText(remark) ? remark.trim() : null)
                .eq(DocNotice::getId, noticeId)
                .eq(DocNotice::getDocType, docType)
                .eq(DocNotice::getDocId, docId)
                .isNull(DocNotice::getHandledTime));
        if (rows == 1) {
            return;
        }
        // 0 行：不是这张单的（路径上的单据 id 和提醒对不上，不让借别的单据的权限处理）还是已经被别人处理了
        boolean mine = noticeMapper.exists(new LambdaQueryWrapper<DocNotice>()
                .eq(DocNotice::getId, noticeId)
                .eq(DocNotice::getDocType, docType)
                .eq(DocNotice::getDocId, docId));
        if (!mine) {
            throw new BizException(404, "提醒不存在: " + noticeId);
        }
        throw new BizException(ErrorCodes.STATE_CHANGED, "这条提醒刚被别人处理了，刷新看看");
    }

    @Override
    public int countPending(BizType docType, long docId) {
        return Math.toIntExact(noticeMapper.selectCount(new LambdaQueryWrapper<DocNotice>()
                .eq(DocNotice::getDocType, docType)
                .eq(DocNotice::getDocId, docId)
                .isNull(DocNotice::getHandledTime)));
    }

    @Override
    public List<DocNoticeGroup> pendingByDoc(Collection<BizType> docTypes, Long ownerId) {
        if (docTypes == null || docTypes.isEmpty()) {
            return List.of();
        }
        // 走 idx_biz_doc_notice_todo（owner_id，handled_time IS NULL 的部分索引）；新的在前，分组后组的顺序就是最新一条的顺序
        List<DocNotice> notices = noticeMapper.selectList(new LambdaQueryWrapper<DocNotice>()
                .in(DocNotice::getDocType, docTypes)
                .isNull(DocNotice::getHandledTime)
                .eq(ownerId != null, DocNotice::getOwnerId, ownerId)
                .orderByDesc(DocNotice::getId));
        List<DocNoticeVO> vos = toVO(notices);
        Map<String, DocNoticeGroup> groups = new LinkedHashMap<>();
        for (int i = 0; i < notices.size(); i++) {
            DocNotice n = notices.get(i);
            groups.computeIfAbsent(n.getDocType() + ":" + n.getDocId(),
                    k -> new DocNoticeGroup(n.getDocType(), n.getDocId(), new ArrayList<>())).notices().add(vos.get(i));
        }
        return List.copyOf(groups.values());
    }

    /** 来源单据按类型一次描述齐，处理人的昵称一次取齐 */
    private List<DocNoticeVO> toVO(List<DocNotice> notices) {
        Map<BizType, Set<Long>> sourceIds = new HashMap<>();
        for (DocNotice n : notices) {
            sourceIds.computeIfAbsent(n.getSourceType(), k -> new HashSet<>()).add(n.getSourceId());
        }
        Map<BizType, Map<Long, DocRef>> sources = new HashMap<>();
        sourceIds.forEach((type, ids) -> sources.put(type, referenceService.describe(type, ids)));
        Set<Long> handlers = notices.stream().map(DocNotice::getHandledBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = userService.nicknames(handlers);
        return notices.stream()
                .map(n -> new DocNoticeVO(n.getId(), sources.get(n.getSourceType()).get(n.getSourceId()), n.getContent(),
                        n.getCreateTime(), n.getHandledBy() == null ? null : names.get(n.getHandledBy()),
                        n.getHandledTime(), n.getHandleRemark()))
                .toList();
    }
}
