package com.lingmao.scm.module.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.entity.DocLog;
import com.lingmao.scm.module.biz.mapper.DocLogMapper;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocLogServiceImpl implements DocLogService {

    private final DocLogMapper docLogMapper;
    private final UserService userService;

    @Override
    public void add(BizType type, long docId, String action, String remark) {
        add(type, docId, action, null, null, remark);
    }

    @Override
    public void add(BizType type, long docId, String action, Enum<?> from, Enum<?> to, String remark) {
        DocLog log = new DocLog();
        log.setDocType(type);
        log.setDocId(docId);
        log.setAction(action);
        log.setFromStatus(from == null ? null : from.name());
        log.setToStatus(to == null ? null : to.name());
        log.setRemark(remark);
        // 和审计列的修改人一个来源（AuditMetaObjectHandler）：调用方不用每次传，也传不错
        log.setOperatorId(LoginUser.currentUserId());
        docLogMapper.insert(log);
    }

    @Override
    public List<DocLogVO> list(BizType type, long docId) {
        return toVO(docLogMapper.selectList(byDoc(type, docId)));
    }

    @Override
    public PageResult<DocLogVO> page(BizType type, long docId, PageQuery query) {
        Page<DocLog> page = docLogMapper.selectPage(query.toPage(), byDoc(type, docId));
        return new PageResult<>(toVO(page.getRecords()), page.getTotal());
    }

    @Override
    public void deleteByDoc(BizType type, long docId) {
        // 删草稿时保留它的操作记录（只增不改）；字段审计在同一事务里记下这次删除。
    }

    /** 新的在前：详情页侧栏先看到最近做了什么（原型的 Log 也是倒着排）。按 id 排，走 (doc_type, doc_id, id) 这个索引 */
    private static LambdaQueryWrapper<DocLog> byDoc(BizType type, long docId) {
        return new LambdaQueryWrapper<DocLog>()
                .eq(DocLog::getDocType, type)
                .eq(DocLog::getDocId, docId)
                .orderByDesc(DocLog::getId);
    }

    /** 操作人的昵称一次取齐（system 的 UserService），不 JOIN sys_user：biz 只能通过 Service 用 system（架构 §3.2） */
    private List<DocLogVO> toVO(List<DocLog> logs) {
        Set<Long> operatorIds = logs.stream().map(DocLog::getOperatorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = userService.nicknames(operatorIds);
        return logs.stream()
                .map(log -> new DocLogVO(log.getId(), log.getAction(), log.getFromStatus(), log.getToStatus(), log.getRemark(),
                        log.getOperatorId(),
                        // 先判空：nicknames 可能返回 Map.of()，它的 get(null) 会抛 NullPointerException
                        log.getOperatorId() == null ? null : names.get(log.getOperatorId()),
                        log.getCreateTime()))
                .toList();
    }
}
