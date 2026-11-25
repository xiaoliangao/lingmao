package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.module.system.dto.DeptSaveReq;
import com.lingmao.scm.module.system.dto.DeptVO;
import com.lingmao.scm.module.system.entity.SysDept;
import com.lingmao.scm.module.system.entity.SysUserDept;
import com.lingmao.scm.module.system.mapper.SysDeptMapper;
import com.lingmao.scm.module.system.mapper.SysUserDeptMapper;
import com.lingmao.scm.module.system.service.DeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 部门（RFC 018 §1）。部门就十几二十个，树、路径、下级都是整表取出来在内存里算，不为它写递归 SQL。
 * 换上级时先锁整张表（同一时间两个人把 A 挪到 B 下、B 挪到 A 下，各自查都不成环，一起提交就成了环）：改部门是管理员偶尔做的事，排队无所谓
 */
@Service
@RequiredArgsConstructor
public class DeptServiceImpl implements DeptService {

    /** 最多几级（RFC 018 §1.2） */
    static final int MAX_LEVEL = 5;
    /** 全路径的分隔：导入时也按它拆 */
    public static final String SEPARATOR = "/";

    private final SysDeptMapper deptMapper;
    private final SysUserDeptMapper userDeptMapper;

    @Override
    public List<DeptVO> tree() {
        Map<Long, Long> counts = userDeptMapper.countByDept().stream()
                .collect(Collectors.toMap(SysUserDeptMapper.DeptCount::deptId, SysUserDeptMapper.DeptCount::n));
        Index index = index();
        Map<Long, DeptVO> nodes = new LinkedHashMap<>();
        for (SysDept d : index.sorted()) {
            nodes.put(d.getId(), new DeptVO(d.getId(), d.getParentId(), d.getName(), d.getSort(), index.path(d.getId()),
                    counts.getOrDefault(d.getId(), 0L)));
        }
        List<DeptVO> roots = new ArrayList<>();
        nodes.values().forEach(n -> {
            DeptVO parent = n.parentId() == null ? null : nodes.get(n.parentId());
            (parent == null ? roots : parent.children()).add(n);
        });
        return roots;
    }

    @Override
    @Transactional
    public Long create(DeptSaveReq req) {
        lockTable();
        Index index = index();
        if (req.getParentId() != null && !index.byId.containsKey(req.getParentId())) {
            throw new BizException("上级部门不存在，可能刚被删了，请刷新");
        }
        if (index.depth(req.getParentId()) + 1 > MAX_LEVEL) {
            throw new BizException("部门最多 " + MAX_LEVEL + " 级，第 " + MAX_LEVEL + " 级下面不能再加");
        }
        String name = req.getName().strip();
        ensureNameUnique(req.getParentId(), name, null);
        SysDept dept = new SysDept();
        dept.setParentId(req.getParentId());
        dept.setName(name);
        dept.setSort(req.getSort());
        deptMapper.insert(dept);
        return dept.getId();
    }

    @Override
    @Transactional
    public void update(long id, DeptSaveReq req) {
        lockTable();
        Index index = index();
        SysDept dept = index.byId.get(id);
        if (dept == null) {
            throw new BizException(404, "部门不存在: " + id);
        }
        Long parentId = req.getParentId();
        if (!Objects.equals(parentId, dept.getParentId())) {
            if (parentId != null && !index.byId.containsKey(parentId)) {
                throw new BizException("上级部门不存在，可能刚被删了，请刷新");
            }
            if (parentId != null && index.descendants(id).contains(parentId)) {
                throw new BizException(400, "不能把部门挪到它自己或它的下级下面");
            }
            // 挪过去以后，最深的那一枝 = 新上级的层数 + 这棵子树有几层
            if (index.depth(parentId) + index.height(id) > MAX_LEVEL) {
                throw new BizException("挪过去就超过 " + MAX_LEVEL + " 级了，请换一个上级");
            }
        }
        String name = req.getName().strip();
        ensureNameUnique(parentId, name, id);
        dept.setParentId(parentId);
        dept.setName(name);
        dept.setSort(req.getSort());
        deptMapper.update(dept, Updates.replaceById(dept));
    }

    @Override
    @Transactional
    public void delete(long id) {
        lockTable();
        if (deptMapper.selectById(id) == null) {
            throw new BizException(404, "部门不存在: " + id);
        }
        List<String> children = deptMapper.selectList(new LambdaQueryWrapper<SysDept>().eq(SysDept::getParentId, id))
                .stream().map(SysDept::getName).toList();
        if (!children.isEmpty()) {
            throw new BizException(ErrorCodes.HAS_DOWNSTREAM, "下面还有 " + children.size() + " 个部门（"
                    + String.join("、", children) + "），先删掉或挪走它们");
        }
        long users = userDeptMapper.selectCount(new LambdaQueryWrapper<SysUserDept>().eq(SysUserDept::getDeptId, id));
        if (users > 0) {
            throw new BizException(ErrorCodes.HAS_DOWNSTREAM, "部门里还有 " + users + " 个人，先在用户里把他们换到别的部门");
        }
        deptMapper.deleteById(id);
    }

    @Override
    public List<Long> withDescendants(long id) {
        Index index = index();
        return index.byId.containsKey(id) ? List.copyOf(index.descendants(id)) : List.of();
    }

    @Override
    public void requireExisting(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<Long> distinct = new HashSet<>(ids);
        long found = deptMapper.selectCount(new LambdaQueryWrapper<SysDept>().in(SysDept::getId, distinct));
        if (found != distinct.size()) {
            throw new BizException(400, "有部门不存在，可能刚被删了，请刷新");
        }
    }

    @Override
    public Map<Long, String> paths() {
        Index index = index();
        Map<Long, String> paths = new HashMap<>();
        index.byId.keySet().forEach(id -> paths.put(id, index.path(id)));
        return paths;
    }

    /** 同一上级下不重名（顶层之间也不重名）；excludeId 是修改时的自己。唯一索引兜底 */
    private void ensureNameUnique(Long parentId, String name, Long excludeId) {
        boolean taken = deptMapper.exists(new LambdaQueryWrapper<SysDept>()
                .eq(parentId != null, SysDept::getParentId, parentId)
                .isNull(parentId == null, SysDept::getParentId)
                .eq(SysDept::getName, name)
                .ne(excludeId != null, SysDept::getId, excludeId));
        if (taken) {
            throw new BizException("同一个上级下已经有「" + name + "」了");
        }
    }

    private void lockTable() {
        deptMapper.lockTable();
    }

    private Index index() {
        return new Index(deptMapper.selectList(null));
    }

    /** 整张表摊开的查法 */
    static final class Index {

        final Map<Long, SysDept> byId = new HashMap<>();
        final Map<Long, List<SysDept>> children = new HashMap<>();

        Index(List<SysDept> all) {
            all.forEach(d -> byId.put(d.getId(), d));
            all.forEach(d -> children.computeIfAbsent(d.getParentId(), k -> new ArrayList<>()).add(d));
        }

        /** 同级按排序、id 排，先上级后下级（深度优先） */
        List<SysDept> sorted() {
            Comparator<SysDept> order = Comparator.comparing(SysDept::getSort, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(SysDept::getId);
            List<SysDept> out = new ArrayList<>();
            Deque<SysDept> stack = new ArrayDeque<>();
            children.getOrDefault(null, List.of()).stream().sorted(order.reversed()).forEach(stack::push);
            while (!stack.isEmpty()) {
                SysDept d = stack.pop();
                out.add(d);
                children.getOrDefault(d.getId(), List.of()).stream().sorted(order.reversed()).forEach(stack::push);
            }
            return out;
        }

        /** 这个部门在第几级：顶层 1；null（还没有上级）是 0 */
        int depth(Long id) {
            int depth = 0;
            for (SysDept d = id == null ? null : byId.get(id); d != null && depth <= MAX_LEVEL * 2;
                 d = d.getParentId() == null ? null : byId.get(d.getParentId())) {
                depth++;
            }
            return depth;
        }

        /** 以它为根的子树有几层：没有下级是 1 */
        int height(long id) {
            int max = 0;
            for (SysDept child : children.getOrDefault(id, List.of())) {
                max = Math.max(max, height(child.getId()));
            }
            return max + 1;
        }

        /** 自己和所有下级 */
        Set<Long> descendants(long id) {
            Set<Long> out = new java.util.LinkedHashSet<>();
            Deque<Long> stack = new ArrayDeque<>(List.of(id));
            while (!stack.isEmpty()) {
                Long current = stack.pop();
                if (out.add(current)) {
                    children.getOrDefault(current, List.of()).forEach(c -> stack.push(c.getId()));
                }
            }
            return out;
        }

        /** 产品部/设计组 */
        String path(long id) {
            Deque<String> names = new ArrayDeque<>();
            int guard = 0;
            for (SysDept d = byId.get(id); d != null && guard++ <= MAX_LEVEL * 2;
                 d = d.getParentId() == null ? null : byId.get(d.getParentId())) {
                names.push(d.getName());
            }
            return String.join(SEPARATOR, names);
        }
    }
}
