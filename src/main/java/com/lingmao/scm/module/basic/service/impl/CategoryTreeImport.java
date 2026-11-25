package com.lingmao.scm.module.basic.service.impl;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelImport;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.common.excel.ImportError;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.module.basic.dto.CategoryExcelRow;
import com.lingmao.scm.module.basic.entity.Category;
import com.lingmao.scm.module.basic.entity.MaterialCategory;
import com.lingmao.scm.module.basic.mapper.CategoryMapper;
import com.lingmao.scm.module.basic.mapper.MaterialCategoryMapper;
import com.lingmao.scm.module.system.service.FileImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 品类、物料分类导入（RFC 018 §8.7）：收领猫"基础档案 › 产品类目 / 物料类目"导出的表。
 * 分类名称是用 > 连起来的路径（如 服装（非通勤裤类）>裙子>半身裙），后面是分类编码、状态、排序；扩展一到扩展七、描述认得、不导。
 * <p>
 * 按路径对：已有的（同一个上级下同名）改编码、状态；没有的建上，排序用表里的。上级要么已经在系统里，要么也在表里。
 * 表里编码、状态空着的，已有的不动（不清掉管理员填过的）。有一行不对整批不导（RFC 001 §9.3）；同一个文件再导一次什么都不变
 */
@Component
@RequiredArgsConstructor
class CategoryTreeImport {

    static final int MAX_ROWS = 2000;
    /** 路径里的分隔：领猫用 >，全角的也认 */
    static final Pattern PATH_SPLIT = Pattern.compile("\\s*[>＞]\\s*");
    /** 路径的 key：各级名称用一个名称里不会有的字符连起来 */
    private static final String SEP = "\u0000";
    /** 编码校验出错时的标记，和"没填"（null）分开 */
    private static final String INVALID = "\u0000invalid";

    private final CategoryMapper categoryMapper;
    private final MaterialCategoryMapper materialCategoryMapper;
    private final FileImportService imports;

    /** 导哪一棵树 */
    enum Kind {
        /** 品类：三级；编码选填，1-8 位字母数字（款号规则里的一级品类编码、品类编码），不要求唯一 */
        CATEGORY("品类", "category-tree", "basic:basics:add", 3, Pattern.compile("^[A-Z0-9]{1,8}$")),
        /** 物料分类：两级；编码 2 位字母或数字，二级必填，同一个上级下不重复（V58） */
        MATERIAL("物料分类", "material-category-tree", "basic:basics:add", 2, Pattern.compile("^[A-Z0-9]{2}$"));

        final String label;
        final String operation;
        final String permission;
        final int maxLevel;
        final Pattern code;

        Kind(String label, String operation, String permission, int maxLevel, Pattern code) {
            this.label = label;
            this.operation = operation;
            this.permission = permission;
            this.maxLevel = maxLevel;
            this.code = code;
        }
    }

    /** 两棵树共用的样子：已有的节点 */
    record Node(Long id, Long parentId, String name, String code, int level, Integer status) {
    }

    /** 表里的一行要做什么；existing 为空 = 新建 */
    record Planned(int row, String path, String parentPath, String name, String code, int level, int sort, Integer status,
                   Node existing) {

        boolean changes() {
            return existing == null || (code != null && !code.equals(existing.code()))
                    || (status != null && !status.equals(existing.status()));
        }
    }

    record Plan(Kind kind, List<Planned> rows, Map<String, Long> existingIds, List<ImportError> notes) {
    }

    ExcelSchema<CategoryExcelRow> schema(Kind kind) {
        boolean material = kind == Kind.MATERIAL;
        return ExcelSchema.of(kind.label, CategoryExcelRow::new,
                        ExcelColumn.text("分类名称", CategoryExcelRow::getPath, CategoryExcelRow::setPath).required().maxLength(200)
                                .example(material ? "拉链>金属拉链" : "女装>外套>风衣")
                                .note("从一级写到这一级，用 > 隔开；上级要么系统里已经有，要么也写在表里"),
                        ExcelColumn.text("分类编码", CategoryExcelRow::getCode, CategoryExcelRow::setCode).code().maxLength(8)
                                .example(material ? "03" : "95")
                                .note(material ? "2 位字母或数字，二级必填；同一个上级下不能一样。空着的，已有的编码不动"
                                        : "选填，1-8 位字母、数字；款号规则里用到品类编码时要填。空着的，已有的编码不动"),
                        ExcelColumn.text("状态", CategoryExcelRow::getStatus, CategoryExcelRow::setStatus).maxLength(8)
                                .example("启用").note("启用 或 停用；空着：新建的启用，已有的不变"),
                        ExcelColumn.integer("排序", CategoryExcelRow::getSort, CategoryExcelRow::setSort)
                                .note("同一级里的顺序，只给新建的用"))
                // 领猫导出里有、系统里没有的几列：认得，不导
                .ignore("扩展一", "扩展二", "扩展三", "扩展四", "扩展五", "扩展六", "扩展七", "描述");
    }

    byte[] template(Kind kind) {
        return Excel.template(schema(kind));
    }

    ImportResult importExcel(Kind kind, MultipartFile file) {
        return imports.execute(kind.operation, "global", kind.permission, file, Map.of(),
                () -> prepare(kind, Excel.read(schema(kind), file)), this::write);
    }

    /** 逐行对上已有的节点、查格式和上级；问题记进 file，有问题整批不导 */
    Plan prepare(Kind kind, ExcelImport<CategoryExcelRow> file) {
        List<ExcelImport.Line<CategoryExcelRow>> lines = file.lines();
        if (lines.size() > MAX_ROWS) {
            file.error(0, null, "一次最多导 " + MAX_ROWS + " 个" + kind.label + "，这个文件有 " + lines.size() + " 行，请分几次导");
            file.dataOrThrow();
        }
        List<Node> nodes = load(kind);
        Map<String, Node> existing = byPath(nodes);
        Map<String, Integer> firstRow = new HashMap<>();
        List<Planned> rows = new ArrayList<>();
        for (ExcelImport.Line<CategoryExcelRow> line : lines) {
            int row = line.row();
            CategoryExcelRow r = line.data();
            List<String> names = Arrays.asList(PATH_SPLIT.split(r.getPath().strip(), -1));
            if (names.stream().anyMatch(String::isEmpty)) {
                file.error(row, "分类名称", "「" + r.getPath() + "」里有空的一级：各级名称用 > 隔开，开头结尾不要 >");
                continue;
            }
            if (names.size() > kind.maxLevel) {
                file.error(row, "分类名称", kind.label + "只有 " + kind.maxLevel + " 级，「" + r.getPath() + "」是 " + names.size() + " 级");
                continue;
            }
            String tooLong = names.stream().filter(n -> n.length() > 50).findFirst().orElse(null);
            if (tooLong != null) {
                file.error(row, "分类名称", "「" + tooLong + "」超过 50 个字");
                continue;
            }
            String path = String.join(SEP, names);
            Integer first = firstRow.putIfAbsent(path, row);
            if (first != null) {
                file.error(row, "分类名称", "和第 " + first + " 行是同一个分类");
                continue;
            }
            Node node = existing.get(path);
            String code = code(kind, file, row, names.size(), r.getCode(), node);
            Integer status = status(file, row, r.getStatus());
            if (Objects.equals(code, INVALID) || Objects.equals(status, -1)) {
                continue;
            }
            String parentPath = names.size() == 1 ? null : String.join(SEP, names.subList(0, names.size() - 1));
            rows.add(new Planned(row, path, parentPath, names.getLast(), code, names.size(),
                    r.getSort() == null ? 0 : r.getSort(), status, node));
        }
        Set<String> inFile = new HashSet<>(firstRow.keySet());
        for (Planned p : rows) {
            if (p.parentPath() != null && !existing.containsKey(p.parentPath()) && !inFile.contains(p.parentPath())) {
                file.error(p.row(), "分类名称", "上级「" + display(p.parentPath()) + "」系统里没有，表里也没有：先在表里加上它");
            }
        }
        if (kind == Kind.MATERIAL) {
            checkCodesUnique(file, rows, nodes, existing);
        }
        file.dataOrThrow();
        List<Integer> changed = rows.stream().filter(p -> p.existing() != null && p.changes()).map(Planned::row).toList();
        List<Integer> same = rows.stream().filter(p -> !p.changes()).map(Planned::row).toList();
        if (!changed.isEmpty()) {
            file.note(0, null, rowsText(changed) + "是系统里已有的" + kind.label + "，按表改了编码或状态");
        }
        if (!same.isEmpty()) {
            file.note(0, null, rowsText(same) + "和系统里已有的一样，没改");
        }
        rows.sort(Comparator.comparingInt(Planned::level).thenComparingInt(Planned::row));
        Map<String, Long> ids = new HashMap<>();
        existing.forEach((path, node) -> ids.put(path, node.id()));
        return new Plan(kind, rows, ids, file.notes());
    }

    /** 编码：转大写、查格式；没填返回 null（物料的二级新建时必填）；不对记错、返回 INVALID */
    private static String code(Kind kind, ExcelImport<CategoryExcelRow> file, int row, int level, String raw, Node existing) {
        String code = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            if (kind == Kind.MATERIAL && level == 2 && (existing == null || existing.code() == null)) {
                file.error(row, "分类编码", "二级分类要填 2 位编码（字母或数字）：物料编码里要用");
                return INVALID;
            }
            return null;
        }
        if (!kind.code.matcher(code).matches()) {
            String hint = kind == Kind.MATERIAL && code.length() == 1 && Character.isDigit(code.charAt(0))
                    ? "：可能是 Excel 把 0" + code + " 当成了数字，把这一列设成文本、重新填" : "";
            file.error(row, "分类编码", "编码「" + raw.strip() + "」不对，" + kind.label + "的编码是"
                    + (kind == Kind.MATERIAL ? " 2 位字母或数字" : " 1-8 位字母、数字") + hint);
            return INVALID;
        }
        return code;
    }

    /** 状态：空着返回 null（新建的启用、已有的不变）；不对记错、返回 -1 */
    private static Integer status(ExcelImport<CategoryExcelRow> file, int row, String raw) {
        String s = raw == null ? "" : raw.strip();
        return switch (s) {
            case "" -> null;
            case "启用" -> Statuses.ENABLED;
            case "停用" -> Statuses.DISABLED;
            default -> {
                file.error(row, "状态", "状态写 启用 或 停用");
                yield -1;
            }
        };
    }

    /** 物料分类编码在同一个上级下不能一样（V58）：按导完以后的样子查，表里的和系统里不在表里的一起算 */
    private static void checkCodesUnique(ExcelImport<CategoryExcelRow> file, List<Planned> rows, List<Node> nodes,
                                         Map<String, Node> existing) {
        Map<Long, String> pathOfId = new HashMap<>();
        existing.forEach((path, node) -> pathOfId.put(node.id(), path));
        Set<String> inFile = new HashSet<>();
        rows.forEach(p -> inFile.add(p.path()));
        // 上级的 key + 编码 → 谁占着（"第 N 行" 或 "系统里的「…」"）；表里有的按表里的算
        Map<String, String> taken = new HashMap<>();
        for (Node node : nodes) {
            String path = pathOfId.get(node.id());
            if (node.code() != null && path != null && !inFile.contains(path)) {
                String parent = node.parentId() == null ? "" : pathOfId.get(node.parentId());
                taken.put(parent + SEP + SEP + node.code(), "系统里的「" + display(path) + "」");
            }
        }
        for (Planned p : rows) {
            String code = p.code() != null ? p.code() : p.existing() == null ? null : p.existing().code();
            if (code == null) {
                continue;
            }
            String key = (p.parentPath() == null ? "" : p.parentPath()) + SEP + SEP + code;
            String other = taken.putIfAbsent(key, "第 " + p.row() + " 行");
            if (other != null) {
                file.error(p.row(), "分类编码", "编码 " + code + " 和" + other + "一样：同一个上级下编码不能重复");
            }
        }
    }

    /** 写库：先建上级再建下级；已有的只改有变化的。在 FileImportService 的事务里 */
    private ImportResult write(Plan plan) {
        Map<String, Long> ids = new HashMap<>(plan.existingIds());
        for (Planned p : plan.rows()) {
            if (p.existing() == null) {
                ids.put(p.path(), insert(plan.kind(), p.parentPath() == null ? null : ids.get(p.parentPath()), p));
            } else if (p.changes()) {
                update(plan.kind(), p);
            }
        }
        return ImportResult.of(plan.rows().size(), plan.notes());
    }

    private long insert(Kind kind, Long parentId, Planned p) {
        int status = p.status() == null ? Statuses.ENABLED : p.status();
        if (kind == Kind.CATEGORY) {
            Category c = new Category();
            c.setParentId(parentId);
            c.setLevel(p.level());
            c.setName(p.name());
            c.setCode(p.code());
            c.setSort(p.sort());
            c.setStatus(status);
            categoryMapper.insert(c);
            return c.getId();
        }
        MaterialCategory c = new MaterialCategory();
        c.setParentId(parentId);
        c.setLevel(p.level());
        c.setName(p.name());
        c.setCode(p.code());
        c.setSort(p.sort());
        c.setStatus(status);
        materialCategoryMapper.insert(c);
        return c.getId();
    }

    private void update(Kind kind, Planned p) {
        long id = p.existing().id();
        if (kind == Kind.CATEGORY) {
            Category c = categoryMapper.selectById(id);
            if (p.code() != null) {
                c.setCode(p.code());
            }
            if (p.status() != null) {
                c.setStatus(p.status());
            }
            categoryMapper.update(c, Updates.replaceById(c));
            return;
        }
        MaterialCategory c = materialCategoryMapper.selectById(id);
        if (p.code() != null) {
            c.setCode(p.code());
        }
        if (p.status() != null) {
            c.setStatus(p.status());
        }
        materialCategoryMapper.update(c, Updates.replaceById(c));
    }

    private List<Node> load(Kind kind) {
        if (kind == Kind.CATEGORY) {
            return categoryMapper.selectList(null).stream()
                    .map(c -> new Node(c.getId(), c.getParentId(), c.getName(), c.getCode(), c.getLevel(), c.getStatus())).toList();
        }
        return materialCategoryMapper.selectList(null).stream()
                .map(c -> new Node(c.getId(), c.getParentId(), c.getName(), c.getCode(), c.getLevel(), c.getStatus())).toList();
    }

    /** 已有的节点按路径（各级名称连起来）找 */
    static Map<String, Node> byPath(List<Node> nodes) {
        Map<Long, Node> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.id(), n));
        Map<String, Node> result = new HashMap<>();
        for (Node node : nodes) {
            List<String> names = new ArrayList<>();
            Node at = node;
            for (int depth = 0; at != null && depth < 10; depth++) {
                names.addFirst(at.name());
                at = at.parentId() == null ? null : byId.get(at.parentId());
            }
            result.put(String.join(SEP, names), node);
        }
        return result;
    }

    /** 提示里列行号：最多列 20 个 */
    static String rowsText(List<Integer> rows) {
        String listed = rows.stream().sorted().limit(20).map(String::valueOf).collect(java.util.stream.Collectors.joining("、"));
        return "第 " + listed + (rows.size() > 20 ? " 等 " + rows.size() + " 行" : " 行");
    }

    private static String display(String path) {
        return path.replace(SEP, ">");
    }
}
