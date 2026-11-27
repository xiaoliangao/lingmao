package com.lingmao.scm.module.production.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.service.CategoryService;
import com.lingmao.scm.module.production.dto.CapacityPreviewVO;
import com.lingmao.scm.module.production.dto.CapacityQuery;
import com.lingmao.scm.module.production.dto.CapacityRowVO;
import com.lingmao.scm.module.production.dto.CapacitySaveReq;
import com.lingmao.scm.module.production.entity.FactoryCapacity;
import com.lingmao.scm.module.production.mapper.FactoryCapacityMapper;
import com.lingmao.scm.module.production.service.FactoryCapacityService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FactoryCapacityServiceImpl implements FactoryCapacityService {

    /** 能登记产能的供应商类型：成衣厂（成品采购）、加工厂（外发加工） */
    private static final Set<String> FACTORY_TYPES = Set.of("GARMENT", "PROCESSOR");

    private final FactoryCapacityMapper capacityMapper;
    private final SupplierService supplierService;
    private final CategoryService categoryService;
    private final UserService userService;
    private final Clock clock;

    @Override
    public List<CapacityRowVO> list(CapacityQuery query) {
        YearMonth ym = query.getMonth() == null ? YearMonth.now(clock) : parseMonth(query.getMonth());
        List<FactoryCapacity> rows = capacityMapper.selectEffective(ym.atDay(1)).stream()
                .filter(r -> query.getFactoryId() == null || query.getFactoryId().equals(r.getFactoryId())).toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> factoryIds = rows.stream().map(FactoryCapacity::getFactoryId).collect(Collectors.toSet());
        Map<Long, SupplierRef> factories = supplierService.refs(factoryIds);
        Categories cats = categories();
        List<FactoryCapacityMapper.LoadRow> loads = capacityMapper.selectLoads(factoryIds, null);
        Map<Long, String> names = userService.nicknames(rows.stream().map(FactoryCapacity::getUpdateBy).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        String kw = query.getKeyword() == null ? "" : query.getKeyword().strip().toLowerCase();
        List<CapacityRowVO> out = new ArrayList<>();
        for (FactoryCapacity r : rows) {
            SupplierRef f = factories.get(r.getFactoryId());
            CapacityRowVO vo = new CapacityRowVO();
            vo.setId(r.getId());
            vo.setFactoryId(r.getFactoryId());
            vo.setFactoryCode(f == null ? null : f.code());
            vo.setFactoryShortName(f == null ? String.valueOf(r.getFactoryId()) : f.shortName());
            vo.setCategoryId(r.getCategoryId());
            vo.setCategoryName(cats.path(r.getCategoryId()));
            vo.setStartMonth(YearMonth.from(r.getStartMonth()).toString());
            vo.setDailyQty(r.getDailyQty());
            vo.setMonthCapacity((long) r.getDailyQty() * ym.lengthOfMonth());
            vo.setMonthLoad(loads.stream().filter(l -> l.factoryId() == r.getFactoryId() && YearMonth.from(l.dueDate()).equals(ym)
                    && cats.covers(r.getCategoryId(), l.categoryId())).mapToLong(FactoryCapacityMapper.LoadRow::qty).sum());
            vo.setRemark(r.getRemark());
            vo.setUpdateByName(r.getUpdateBy() == null ? null : names.get(r.getUpdateBy()));
            vo.setUpdateTime(r.getUpdateTime());
            if (kw.isEmpty() || contains(kw, vo.getFactoryShortName(), f == null ? null : f.name(), vo.getFactoryCode(), vo.getCategoryName())) {
                out.add(vo);
            }
        }
        out.sort(Comparator.comparing(CapacityRowVO::getFactoryShortName)
                .thenComparing(CapacityRowVO::getCategoryName, Comparator.nullsFirst(Comparator.naturalOrder())));
        return out;
    }

    private static boolean contains(String kw, String... texts) {
        for (String t : texts) {
            if (t != null && t.toLowerCase().contains(kw)) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional
    public Long save(CapacitySaveReq req) {
        SupplierRef f = supplierService.refs(List.of(req.getFactoryId())).get(req.getFactoryId());
        if (f == null) {
            throw new BizException(404, "加工厂不存在");
        }
        if (supplierService.categories(List.of(f.id())).getOrDefault(f.id(), List.of()).stream().noneMatch(FACTORY_TYPES::contains)) {
            throw new BizException(400, f.shortName() + " 不是成衣厂、加工厂，不用登记产能");
        }
        if (req.getCategoryId() != null) {
            categoryService.getById(req.getCategoryId());
        }
        LocalDate month = parseMonth(req.getMonth()).atDay(1);
        String remark = req.getRemark() == null || req.getRemark().isBlank() ? null : req.getRemark().strip();
        FactoryCapacity old = capacityMapper.selectOne(new LambdaQueryWrapper<FactoryCapacity>()
                .eq(FactoryCapacity::getFactoryId, f.id())
                .eq(req.getCategoryId() != null, FactoryCapacity::getCategoryId, req.getCategoryId())
                .isNull(req.getCategoryId() == null, FactoryCapacity::getCategoryId)
                .eq(FactoryCapacity::getStartMonth, month));
        if (old != null) {
            old.setDailyQty(req.getDailyQty());
            old.setRemark(remark);
            capacityMapper.updateById(old);
            return old.getId();
        }
        FactoryCapacity c = new FactoryCapacity();
        c.setFactoryId(f.id());
        c.setCategoryId(req.getCategoryId());
        c.setStartMonth(month);
        c.setDailyQty(req.getDailyQty());
        c.setRemark(remark);
        capacityMapper.insert(c);
        return c.getId();
    }

    @Override
    @Transactional
    public void delete(long id) {
        if (capacityMapper.deleteById(id) == 0) {
            throw new BizException(404, "这条产能已经删了");
        }
    }

    @Override
    public List<CapacityPreviewVO> preview(long factoryId, long styleId, LocalDate dueDate, Long excludeId) {
        Plan plan = plan(Set.of(factoryId), Set.of(styleId), excludeId == null ? List.of() : List.of(excludeId));
        LocalDate today = LocalDate.now(clock);
        return plan.lines(factoryId, styleId).stream().map(line -> {
            CapacityRules.Room room = CapacityRules.room(line.daily(), plan.loads(line, List.of()), dueDate, today);
            return new CapacityPreviewVO(plan.cats().path(line.categoryId()), CapacityRules.dailyAt(line.daily(), room.until()),
                    room.until(), room.capacity(), room.load(), room.free());
        }).toList();
    }

    @Override
    public List<String> check(List<Demand> demands, Collection<Long> exclude) {
        List<Demand> todo = demands.stream().filter(d -> d.dueDate() != null && d.qty() > 0).toList();
        if (todo.isEmpty()) {
            return List.of();
        }
        Plan plan = plan(todo.stream().map(Demand::factoryId).collect(Collectors.toSet()),
                todo.stream().map(Demand::styleId).collect(Collectors.toSet()), exclude);
        if (plan.capacities().isEmpty()) {
            return List.of();
        }
        LocalDate today = LocalDate.now(clock);
        Map<Long, SupplierRef> factories = supplierService.refs(todo.stream().map(Demand::factoryId).collect(Collectors.toSet()));
        List<String> problems = new ArrayList<>();
        List<Demand> done = new ArrayList<>();
        for (Demand d : todo) {
            for (Line line : plan.lines(d.factoryId(), d.styleId())) {
                CapacityRules.Room room = CapacityRules.room(line.daily(), plan.loads(line, done), d.dueDate(), today);
                if (d.qty() > room.free()) {
                    SupplierRef f = factories.get(d.factoryId());
                    String cat = plan.cats().path(line.categoryId());
                    problems.add(d.where() + "加工厂 " + (f == null ? d.factoryId() : f.shortName()) + (cat == null ? "" : "「" + cat + "」")
                            + " 排满了：到 " + ProdTexts.md(room.until()) + " 能做 " + room.capacity() + " 件（每天 "
                            + CapacityRules.dailyAt(line.daily(), room.until()) + " 件），交期在这之前还没做完的 " + room.load()
                            + " 件，这次 " + d.qty() + " 件，超了 " + (d.qty() - Math.max(room.free(), 0)) + " 件。"
                            + "改交期、换工厂，或者先在「生产 › 工厂产能」里改产能");
                }
            }
            done.add(d);
        }
        return problems;
    }

    // ---------------------------------------------------------------- 算的时候要的数

    /** 一条产能：一家厂 × 一个品类（null = 不分），各月的日产能 */
    private record Line(long factoryId, Long categoryId, NavigableMap<LocalDate, Integer> daily) {
    }

    private record Plan(Map<Long, List<Line>> capacities, Map<Long, Long> styleCats, List<FactoryCapacityMapper.LoadRow> loadRows,
                        Categories cats) {

        /** 管这个款的产能：这家厂不分品类的，和挂在这个款的品类或它上级的 */
        List<Line> lines(long factoryId, long styleId) {
            Long cat = styleCats.get(styleId);
            return capacities.getOrDefault(factoryId, List.of()).stream().filter(l -> cats.covers(l.categoryId(), cat)).toList();
        }

        /** 这条产能管的、还没做完的：交期 → 件数，加上同一个请求里先排的几块 */
        NavigableMap<LocalDate, Long> loads(Line line, List<Demand> earlier) {
            NavigableMap<LocalDate, Long> out = new TreeMap<>();
            for (FactoryCapacityMapper.LoadRow r : loadRows) {
                if (r.factoryId() == line.factoryId() && cats.covers(line.categoryId(), r.categoryId())) {
                    out.merge(r.dueDate(), r.qty(), Long::sum);
                }
            }
            for (Demand d : earlier) {
                if (d.factoryId() == line.factoryId() && cats.covers(line.categoryId(), styleCats.get(d.styleId()))) {
                    out.merge(d.dueDate(), (long) d.qty(), Long::sum);
                }
            }
            return out;
        }
    }

    private Plan plan(Set<Long> factoryIds, Set<Long> styleIds, Collection<Long> exclude) {
        Map<Long, Map<Long, NavigableMap<LocalDate, Integer>>> grouped = new HashMap<>();
        for (FactoryCapacity c : capacityMapper.selectByFactories(factoryIds)) {
            grouped.computeIfAbsent(c.getFactoryId(), k -> new HashMap<>())
                    .computeIfAbsent(c.getCategoryId() == null ? 0L : c.getCategoryId(), k -> new TreeMap<>())
                    .put(c.getStartMonth(), c.getDailyQty());
        }
        Map<Long, List<Line>> capacities = new HashMap<>();
        grouped.forEach((factory, byCat) -> byCat.forEach((cat, daily) ->
                capacities.computeIfAbsent(factory, k -> new ArrayList<>()).add(new Line(factory, cat == 0L ? null : cat, daily))));
        if (capacities.isEmpty()) {
            return new Plan(Map.of(), Map.of(), List.of(), new Categories(Map.of(), Map.of()));
        }
        Map<Long, Long> styleCats = new HashMap<>();
        capacityMapper.selectStyleCategories(styleIds).forEach(r -> styleCats.put(r.id(), r.categoryId()));
        return new Plan(capacities, styleCats, capacityMapper.selectLoads(capacities.keySet(), exclude), categories());
    }

    /** 品类树：上级、名字（停用的也在，老款还挂着） */
    private record Categories(Map<Long, Long> parents, Map<Long, String> names) {

        /** line 管不管 cat：line 是 null（不分品类），或者 cat 就是它、是它下面的 */
        boolean covers(Long line, Long cat) {
            if (line == null) {
                return true;
            }
            Set<Long> seen = new HashSet<>();
            for (Long c = cat; c != null && seen.add(c); c = parents.get(c)) {
                if (c.equals(line)) {
                    return true;
                }
            }
            return false;
        }

        /** "女装 / 外套"；null 是 null */
        String path(Long id) {
            if (id == null) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (Long c = id; c != null && seen.add(c); c = parents.get(c)) {
                parts.addFirst(names.getOrDefault(c, String.valueOf(c)));
            }
            return String.join(" / ", parts);
        }
    }

    private Categories categories() {
        Map<Long, Long> parents = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        walk(categoryService.tree(), parents, names);
        return new Categories(parents, names);
    }

    private static void walk(List<TreeNode> nodes, Map<Long, Long> parents, Map<Long, String> names) {
        for (TreeNode n : nodes) {
            if (n.getParentId() != null && n.getParentId() != 0) {
                parents.put(n.getId(), n.getParentId());
            }
            names.put(n.getId(), n.getName());
            walk(n.getChildren(), parents, names);
        }
    }

    private static YearMonth parseMonth(String s) {
        try {
            return YearMonth.parse(s);
        } catch (RuntimeException e) {
            throw new BizException(400, "月份写成 2026-12 这样");
        }
    }
}
