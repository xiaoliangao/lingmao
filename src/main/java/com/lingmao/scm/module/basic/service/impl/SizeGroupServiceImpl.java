package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.module.basic.dto.SizeGroupCreateReq;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.basic.entity.SizeGroup;
import com.lingmao.scm.module.basic.entity.SizeGroupItem;
import com.lingmao.scm.module.basic.mapper.SizeGroupItemMapper;
import com.lingmao.scm.module.basic.mapper.SizeGroupMapper;
import com.lingmao.scm.module.basic.service.SizeGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SizeGroupServiceImpl implements SizeGroupService {

    /** 尺码之间的分隔：空白、半角全角逗号、顿号 */
    private static final String SEPARATORS = "[\\s,，、]+";
    static final int MAX_SIZE_LENGTH = 16;

    private final SizeGroupMapper sizeGroupMapper;
    private final SizeGroupItemMapper sizeGroupItemMapper;

    @Override
    public List<SizeGroupVO> list() {
        return toVOs(sizeGroupMapper.selectList(new LambdaQueryWrapper<SizeGroup>().orderByAsc(SizeGroup::getName)));
    }

    @Override
    public List<SizeGroupVO> options() {
        return toVOs(sizeGroupMapper.selectList(new LambdaQueryWrapper<SizeGroup>()
                .eq(SizeGroup::getStatus, Statuses.ENABLED)
                .orderByAsc(SizeGroup::getName)));
    }

    @Override
    public SizeGroupVO getById(long id) {
        return toVOs(List.of(getEntity(id))).getFirst();
    }

    @Override
    public Map<Long, SizeGroupVO> getByIds(Collection<Long> ids) {
        List<Long> wanted = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) return Map.of();
        Map<Long, SizeGroupVO> found = toVOs(sizeGroupMapper.selectByIds(wanted)).stream()
                .collect(Collectors.toMap(SizeGroupVO::id, group -> group));
        for (Long id : wanted) {
            if (!found.containsKey(id)) throw new BizException(404, "尺码组不存在: " + id);
        }
        return found;
    }

    @Override
    @Transactional
    public Long create(SizeGroupCreateReq req) {
        String name = Texts.trim(req.getName());
        ensureNameUnique(name, null);
        List<String> sizes = parse(req.getSizes());
        if (sizes.isEmpty()) {
            throw new BizException(400, "至少填一个尺码");
        }
        String base = Texts.upper(req.getBaseSize());
        if (base == null) {
            base = sizes.get((sizes.size() - 1) / 2);   // 基码默认取中间那个（PRD §6.1.3），偶数个取靠前的
        } else if (!sizes.contains(base)) {
            throw new BizException("基码 " + base + " 不在这组尺码里");
        }
        SizeGroup group = new SizeGroup();
        group.setName(name);
        group.setBaseSize(base);
        sizeGroupMapper.insert(group);
        insertItems(group.getId(), sizes, 1);
        return group.getId();
    }

    @Override
    @Transactional
    public void rename(long id, String name) {
        SizeGroup group = getEntity(id);
        String trimmed = Texts.trim(name);
        ensureNameUnique(trimmed, id);
        group.setName(trimmed);
        sizeGroupMapper.update(group, Updates.replaceById(group));
    }

    @Override
    @Transactional
    public void appendSizes(long id, String sizes) {
        getEntity(id);
        List<String> added = parse(sizes);
        if (added.isEmpty()) {
            throw new BizException(400, "至少填一个尺码");
        }
        List<SizeGroupItem> items = items(List.of(id)).getOrDefault(id, List.of());
        Set<String> existing = items.stream().map(SizeGroupItem::getSizeCode).collect(Collectors.toSet());
        List<String> dup = added.stream().filter(existing::contains).toList();
        if (!dup.isEmpty()) {
            throw new BizException("这组里已经有 " + String.join("、", dup) + " 了");
        }
        int maxSeq = items.stream().mapToInt(SizeGroupItem::getSeq).max().orElse(0);
        // 两个人同时追加：seq 撞上 uk_bas_size_item_seq，后一个报 1000（刷新再试）
        insertItems(id, added, maxSeq + 1);
    }

    @Override
    @Transactional
    public void delete(long id) {
        getEntity(id);
        sizeGroupItemMapper.delete(new LambdaQueryWrapper<SizeGroupItem>().eq(SizeGroupItem::getSizeGroupId, id));
        sizeGroupMapper.deleteById(id);   // 被款式引用了，外键拦住 → 1005，上面删的尺码一起回滚
    }

    @Override
    @Transactional
    public void enable(long id) {
        Statuses.set(sizeGroupMapper, SizeGroup::new, id, Statuses.ENABLED, "尺码组");
    }

    @Override
    @Transactional
    public void disable(long id) {
        Statuses.set(sizeGroupMapper, SizeGroup::new, id, Statuses.DISABLED, "尺码组");
    }

    /**
     * 拆开一串尺码：按空白、逗号、顿号拆，转大写，每个 1-16 个字；这次填的互相重复报 1000。
     * 保持填的顺序
     */
    static List<String> parse(String text) {
        if (text == null) {
            return List.of();
        }
        Set<String> sizes = new LinkedHashSet<>();
        for (String raw : text.strip().split(SEPARATORS)) {
            String size = Texts.upper(raw);
            if (size == null) {
                continue;
            }
            if (size.length() > MAX_SIZE_LENGTH) {
                throw new BizException(400, "尺码 " + size + " 太长了，最多 " + MAX_SIZE_LENGTH + " 个字");
            }
            if (!sizes.add(size)) {
                throw new BizException("尺码 " + size + " 填了两次");
            }
        }
        return new ArrayList<>(sizes);
    }

    private void insertItems(long groupId, List<String> sizes, int firstSeq) {
        List<SizeGroupItem> rows = new ArrayList<>();
        for (int i = 0; i < sizes.size(); i++) {
            SizeGroupItem item = new SizeGroupItem();
            item.setSizeGroupId(groupId);
            item.setSeq(firstSeq + i);
            item.setSizeCode(sizes.get(i));
            rows.add(item);
        }
        sizeGroupItemMapper.insert(rows);
    }

    private SizeGroup getEntity(long id) {
        SizeGroup group = sizeGroupMapper.selectById(id);
        if (group == null) {
            throw new BizException(404, "尺码组不存在: " + id);
        }
        return group;
    }

    private void ensureNameUnique(String name, Long excludeId) {
        boolean taken = sizeGroupMapper.exists(new LambdaQueryWrapper<SizeGroup>()
                .eq(SizeGroup::getName, name)
                .ne(excludeId != null, SizeGroup::getId, excludeId));
        if (taken) {
            throw new BizException("尺码组「" + name + "」已经有了");
        }
    }

    /** 一批尺码组的尺码，组 id → 按顺序的尺码 */
    private Map<Long, List<SizeGroupItem>> items(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        return sizeGroupItemMapper.selectList(new LambdaQueryWrapper<SizeGroupItem>()
                        .in(SizeGroupItem::getSizeGroupId, groupIds)
                        .orderByAsc(SizeGroupItem::getSeq))
                .stream().collect(Collectors.groupingBy(SizeGroupItem::getSizeGroupId));
    }

    private List<SizeGroupVO> toVOs(List<SizeGroup> groups) {
        Map<Long, List<SizeGroupItem>> items = items(groups.stream().map(SizeGroup::getId).toList());
        return groups.stream().map(g -> new SizeGroupVO(g.getId(), g.getName(), g.getBaseSize(), g.getStatus(),
                items.getOrDefault(g.getId(), List.of()).stream().map(SizeGroupItem::getSizeCode).toList())).toList();
    }
}
