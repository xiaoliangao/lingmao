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
    public void deleteByDoc(BizType docType, long docId) {
        noticeMapper.delete(new LambdaQueryWrapper<DocNotice>()
                .eq(DocNotice::getDocType, docType)
                .eq(DocNotice::getDocId, docId));
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

    @Override
    public com.lingmao.scm.module.biz.dto.PendingNoticePage pendingPage(Collection<BizType> requested, Long ownerId,
            com.lingmao.scm.module.biz.dto.PendingNoticePosition after, boolean oldest, int limit) {
        if (limit < 1 || limit > 100 || (oldest && after != null)) {
            throw new BizException(400, "提醒分页参数无效");
        }
        Set<BizType> types = new java.util.LinkedHashSet<>();
        if (LoginUser.hasAuthority("production:order:list")) types.add(BizType.PRODUCTION_ORDER);
        if (LoginUser.hasAuthority("purchase:order:list")) types.add(BizType.PURCHASE_ORDER);
        if (LoginUser.hasAuthority("purchase:mrp:list")) types.add(BizType.MRP_CALC);
        if (requested != null) types.retainAll(requested);
        if (types.isEmpty()) return new com.lingmao.scm.module.biz.dto.PendingNoticePage(List.of(), 0, false);
        var scope = com.lingmao.scm.security.DataScope.current(false);
        long total = noticeMapper.countPendingGroups(types, ownerId, scope);
        var found = noticeMapper.selectPendingGroups(types, ownerId, scope,
                after == null ? null : after.latest(), after == null ? null : after.docType(),
                after == null ? null : after.docId(), oldest, limit + 1);
        boolean hasMore = found.size() > limit;
        var groups = found.subList(0, Math.min(limit, found.size()));
        if (groups.isEmpty()) return new com.lingmao.scm.module.biz.dto.PendingNoticePage(List.of(), total, false);
        List<DocNotice> notices = noticeMapper.selectPendingPreviews(groups, ownerId, scope, oldest ? 1 : 100);
        List<DocNoticeVO> vos = toVO(notices);
        Map<String, List<DocNoticeVO>> byGroup = new LinkedHashMap<>();
        for (int i = 0; i < notices.size(); i++) {
            DocNotice n = notices.get(i);
            byGroup.computeIfAbsent(n.getDocType() + ":" + n.getDocId(), k -> new ArrayList<>()).add(vos.get(i));
        }
        return new com.lingmao.scm.module.biz.dto.PendingNoticePage(groups.stream().map(g ->
                new com.lingmao.scm.module.biz.dto.PendingNoticePage.Group(g.docType(), g.docId(), g.latest(), g.noticesTotal(),
                        List.copyOf(byGroup.getOrDefault(g.docType() + ":" + g.docId(), List.of())))).toList(), total, hasMore);
    }

    /** 来源单据按类型一次描述齐，处理人的昵称一次取齐 */
    private List<DocNoticeVO> toVO(List<DocNotice> notices) {
        Map<BizType, Set<Long>> sourceIds = new HashMap<>();
        for (DocNotice n : notices) {
            sourceIds.computeIfAbsent(n.getSourceType(), k -> new HashSet<>()).add(n.getSourceId());
        }
        Map<BizType, Map<Long, DocRef>> sources = new HashMap<>();
        sourceIds.forEach((type, ids) -> {
            List<Long> batch = List.copyOf(ids);
            Map<Long, DocRef> refs = new HashMap<>();
            for (int from = 0; from < batch.size(); from += 500) {
                refs.putAll(referenceService.describe(type, batch.subList(from, Math.min(batch.size(), from + 500))));
            }
            sources.put(type, refs);
        });
        Set<Long> handlers = notices.stream().map(DocNotice::getHandledBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = userService.nicknames(handlers);
        return notices.stream()
                .map(n -> new DocNoticeVO(n.getId(), sources.get(n.getSourceType()).get(n.getSourceId()), n.getContent(),
                        n.getCreateTime(), n.getHandledBy() == null ? null : names.get(n.getHandledBy()),
                        n.getHandledTime(), n.getHandleRemark()))
                .toList();
    }
}
