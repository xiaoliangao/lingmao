package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelImport;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.common.excel.ImportError;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.dto.UserExcelRow;
import com.lingmao.scm.module.system.entity.SysRole;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.entity.SysUserDept;
import com.lingmao.scm.module.system.entity.SysUserRole;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysUserDeptMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.mapper.SysUserRoleMapper;
import com.lingmao.scm.module.system.service.DefaultCompanyProvider;
import com.lingmao.scm.module.system.service.DeptService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.FileImportService;
import com.lingmao.scm.module.system.service.UserImportService;
import com.lingmao.scm.security.LineScope;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 账号导入（RFC 018 §3）。解析在事务外（FileImportService 的约定），写库在一个事务里：
 * 有一行不对整批不导；登录名已经有了的跳过、进提示，改好文件再导一次不会重复建。
 * 所有人一个初始密码，哈希只算一次（BCrypt 一次一百毫秒上下，四十个人一个个算要好几秒），每个人第一次登录必须改（RFC 018 §2）
 */
@Service
@RequiredArgsConstructor
public class UserImportServiceImpl implements UserImportService {

    /** 一次最多导多少个账号 */
    static final int MAX_ROWS = 500;
    static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,32}$");
    /** 拿来当登录名的手机号：11 位数字 */
    static final Pattern MOBILE = Pattern.compile("^1\\d{10}$");
    /** 一格里写几个（部门、角色、品类线）时的分隔：中英文分号、顿号、逗号、换行 */
    static final Pattern SPLIT = Pattern.compile("[；;、,，\\r\\n]+");
    static final Pattern DESIGNER_NO = Pattern.compile("^\\d{1,2}$");
    /** 提示里一次最多列几个行号 */
    static final int ROWS_LISTED = 20;

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserDeptMapper userDeptMapper;
    private final SysRoleMapper roleMapper;
    private final DeptService deptService;
    private final DictDataService dictDataService;
    private final DefaultCompanyProvider companies;
    private final PasswordEncoder passwordEncoder;
    private final FileImportService imports;

    ExcelSchema<UserExcelRow> schema() {
        return ExcelSchema.of("账号", UserExcelRow::new,
                ExcelColumn.text("登录名", UserExcelRow::getUsername, UserExcelRow::setUsername).code().maxLength(64)
                        .example("xiaolin").note("3-32 位小写字母、数字、下划线。空着或不合这个格式的（领猫的登录名是中文）用手机号当登录名"),
                ExcelColumn.text("昵称", UserExcelRow::getNickname, UserExcelRow::setNickname).required().maxLength(64)
                        .example("小林").note("系统里显示的名字，大家都用花名"),
                ExcelColumn.text("电话", UserExcelRow::getPhone, UserExcelRow::setPhone).code().maxLength(20)
                        .note("手机号；登录名用不了时用它登录"),
                ExcelColumn.text("所属部门", UserExcelRow::getDepts, UserExcelRow::setDepts).maxLength(500)
                        .example("设计组；版师").note("几个部门用分号、顿号或逗号隔开；按名字在部门树里找，有重名的写全路径，如 产品部/设计组"),
                ExcelColumn.text("职位", UserExcelRow::getPost, UserExcelRow::setPost).maxLength(64),
                ExcelColumn.text("角色", UserExcelRow::getRoles, UserExcelRow::setRoles).maxLength(500)
                        .example("开发").note("角色名称，几个用分号隔开；空着的建出来没有角色，导完在列表里勾上批量加"),
                ExcelColumn.text("品类线", UserExcelRow::getProductLines, UserExcelRow::setProductLines).maxLength(100)
                        .example("服装").note("服装、鞋类、百货，几个用分号隔开；空着 = 三条都给"),
                ExcelColumn.text("设计师编号", UserExcelRow::getDesignerNo, UserExcelRow::setDesignerNo).code().maxLength(2)
                        .example("03").note("1-99，会补成两位；进款号，用过就不能改。不是设计师不填"),
                ExcelColumn.text("默认主体", UserExcelRow::getCompany, UserExcelRow::setCompany).maxLength(128)
                        .note("主体公司的编码、简称或全称；新建单据时带出来"),
                ExcelColumn.text("启用", UserExcelRow::getEnabled, UserExcelRow::setEnabled).maxLength(8)
                        .note("启用 或 停用；空着 = 启用"))
                // 领猫导出里有、系统里没有的几列：认得，不导
                .ignore("性别", "邮箱", "内部编码");
    }

    @Override
    public byte[] template() {
        return Excel.template(schema());
    }

    @Override
    public ImportResult importExcel(MultipartFile file, String password) {
        checkPassword(password);
        // options 不放密码：导入回执里存的是它的指纹，密码的指纹也不该进库
        return imports.execute("user", "global", "system:user:import", file, Map.of(),
                () -> prepare(Excel.read(schema(), file)), plan -> write(plan, password));
    }

    static void checkPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 32) {
            throw new BizException(400, "初始密码要 8-32 位");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BizException(400, "初始密码太长：最多 72 个字节，一个汉字算 3 个");
        }
    }

    /** 一个要建的账号 */
    record Prepared(SysUser user, List<Long> deptIds, List<Long> roleIds) {
    }

    record Plan(List<Prepared> rows, List<ImportError> notes) {
    }

    /** 逐行翻成账号；问题记进 file（拦导入），提示攒成几条（不拦） */
    Plan prepare(ExcelImport<UserExcelRow> file) {
        List<ExcelImport.Line<UserExcelRow>> lines = file.lines();
        if (lines.size() > MAX_ROWS) {
            file.error(0, null, "一次最多导 " + MAX_ROWS + " 个账号，这个文件有 " + lines.size() + " 行，请分几次导");
            file.dataOrThrow();
        }
        Map<Integer, String> usernames = new LinkedHashMap<>();
        for (ExcelImport.Line<UserExcelRow> line : lines) {
            String username = username(file, line);
            if (username != null) {
                usernames.put(line.row(), username);
            }
        }
        Set<String> existing = usernames.isEmpty() ? Set.of() : userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .select(SysUser::getUsername).in(SysUser::getUsername, new HashSet<>(usernames.values())))
                .stream().map(SysUser::getUsername).collect(Collectors.toSet());
        Set<String> designerNos = lines.stream().map(l -> designerNo(l.data().getDesignerNo())).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> takenDesignerNos = designerNos.isEmpty() ? Map.of() : userMapper.selectDesignerNoOwners(designerNos)
                .stream().collect(Collectors.toMap(SysUser::getDesignerNo, SysUser::getNickname, (a, b) -> a));
        DeptMatcher depts = new DeptMatcher(deptService.paths());
        RoleMatcher roles = new RoleMatcher(roleMapper.selectList(null));
        LineMatcher productLines = new LineMatcher(dictDataService.listByType(LineScope.DICT_TYPE));

        Map<String, Integer> firstRowOfUsername = new HashMap<>();
        Map<String, Integer> firstRowOfDesignerNo = new HashMap<>();
        List<Integer> skipped = new ArrayList<>();
        List<Integer> phoneLogins = new ArrayList<>();
        List<Integer> noRoles = new ArrayList<>();
        List<Prepared> rows = new ArrayList<>();
        for (ExcelImport.Line<UserExcelRow> line : lines) {
            int row = line.row();
            UserExcelRow r = line.data();
            String username = usernames.get(row);
            if (username == null) {
                continue;   // 登录名的问题已经记了
            }
            Integer first = firstRowOfUsername.putIfAbsent(username, row);
            if (first != null) {
                file.error(row, "登录名", "登录名 " + username + " 和第 " + first + " 行的一样");
                continue;
            }
            if (existing.contains(username)) {
                skipped.add(row);
                continue;
            }
            if (!username.equals(normalizedUsername(r.getUsername()))) {
                phoneLogins.add(row);
            }
            SysUser user = new SysUser();
            user.setUsername(username);
            user.setNickname(r.getNickname());
            user.setPhone(blankToNull(r.getPhone()));
            user.setPost(blankToNull(r.getPost()));
            user.setStatus(status(file, row, r.getEnabled()));
            user.setProductLines(productLines.resolve(file, row, r.getProductLines()));
            user.setPwdMustChange(true);
            String designerNo = designerNo(r.getDesignerNo());
            if (r.getDesignerNo() != null && designerNo == null) {
                file.error(row, "设计师编号", "设计师编号是 1-99 的数字");
            } else if (designerNo != null) {
                Integer firstNo = firstRowOfDesignerNo.putIfAbsent(designerNo, row);
                if (firstNo != null) {
                    file.error(row, "设计师编号", "设计师编号 " + designerNo + " 和第 " + firstNo + " 行的一样");
                } else if (takenDesignerNos.containsKey(designerNo)) {
                    file.error(row, "设计师编号", "设计师编号 " + designerNo + " 已经给了" + takenDesignerNos.get(designerNo)
                            + "（编号不回收，删了的账号也算）");
                }
                user.setDesignerNo(designerNo);
            }
            if (r.getCompany() != null) {
                Long companyId = companies.findEnabledId(r.getCompany());
                if (companyId == null) {
                    file.error(row, "默认主体", "找不到启用的主体公司「" + r.getCompany() + "」：填编码、简称或全称");
                }
                user.setDefaultCompanyId(companyId);
            }
            List<Long> deptIds = depts.resolve(file, row, r.getDepts());
            List<Long> roleIds = roles.resolve(file, row, r.getRoles());
            if (roleIds.isEmpty()) {
                noRoles.add(row);
            }
            rows.add(new Prepared(user, deptIds, roleIds));
        }
        file.dataOrThrow();   // 有问题整批不导，抛 1003

        // 提示合成几条说（ImportResult 的约定），行号写进话里，row 填 0
        if (!skipped.isEmpty()) {
            file.note(0, null, rowsText(skipped) + "的登录名已经有了，跳过没导（没改那几个账号）");
        }
        if (!phoneLogins.isEmpty()) {
            file.note(0, null, rowsText(phoneLogins) + "的登录名用手机号：表里的登录名空着，或者不是小写字母、数字、下划线");
        }
        if (!noRoles.isEmpty()) {
            file.note(0, null, rowsText(noRoles) + "没填角色，登录后什么都看不到：在用户列表里勾上他们，批量加角色");
        }
        return new Plan(rows, file.notes());
    }

    private ImportResult write(Plan plan, String password) {
        String hash = passwordEncoder.encode(password);
        for (Prepared p : plan.rows()) {
            SysUser user = p.user();
            user.setPassword(hash);
            userMapper.insert(user);   // 和别人同时建了同一个登录名、同一个设计师编号：唯一索引拦住（1000），整批回滚
            if (!p.deptIds().isEmpty()) {
                userDeptMapper.insert(p.deptIds().stream().map(d -> new SysUserDept(user.getId(), d)).toList());
            }
            if (!p.roleIds().isEmpty()) {
                userRoleMapper.insert(p.roleIds().stream().map(r -> new SysUserRole(user.getId(), r)).toList());
            }
        }
        return ImportResult.of(plan.rows().size(), plan.notes());
    }

    /** 这一行用什么登录名：表里的合格就用它（转小写），不然用 11 位手机号；都不行记问题、返回 null */
    private static String username(ExcelImport<UserExcelRow> file, ExcelImport.Line<UserExcelRow> line) {
        UserExcelRow r = line.data();
        String given = normalizedUsername(r.getUsername());
        if (given != null && USERNAME.matcher(given).matches()) {
            return given;
        }
        String phone = r.getPhone() == null ? null : r.getPhone().replaceAll("[\\s-]", "");
        if (phone != null && MOBILE.matcher(phone).matches()) {
            return phone;
        }
        file.error(line.row(), "登录名", given == null
                ? "登录名空着，电话也不是 11 位手机号：没法建登录名"
                : "登录名「" + r.getUsername() + "」只能是 3-32 位小写字母、数字、下划线；电话也不是 11 位手机号，不能拿它当登录名");
        return null;
    }

    private static String normalizedUsername(String text) {
        return text == null || text.isBlank() ? null : text.strip().toLowerCase(Locale.ROOT);
    }

    /** 1-99 补成两位；空的返回 null，不是 1-99 的数字也返回 null（调用方区分） */
    static String designerNo(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip();
        if (!DESIGNER_NO.matcher(t).matches()) {
            return null;
        }
        int n = Integer.parseInt(t);
        return n < 1 ? null : String.format("%02d", n);
    }

    private static Integer status(ExcelImport<UserExcelRow> file, int row, String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "启用", "是", "1", "true", "正常" -> 1;
            case "停用", "禁用", "否", "0", "false" -> 0;
            default -> {
                file.error(row, "启用", "填 启用 或 停用");
                yield 1;
            }
        };
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return SPLIT.splitAsStream(text).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** 第 2、3、5 行；太多只列前 20 个 */
    static String rowsText(List<Integer> rows) {
        String listed = rows.stream().limit(ROWS_LISTED).map(String::valueOf).collect(Collectors.joining("、"));
        return "第 " + listed + (rows.size() > ROWS_LISTED ? " 等 " + rows.size() + " 行" : " 行");
    }

    /** 部门：写全路径的按路径找；只写名字的按本级名字找，重名的要写全路径 */
    static final class DeptMatcher {
        private final Map<String, Long> byPath = new HashMap<>();
        private final Map<String, List<Long>> byName = new HashMap<>();

        DeptMatcher(Map<Long, String> paths) {
            paths.forEach((id, path) -> {
                byPath.put(path, id);
                String name = path.substring(path.lastIndexOf(DeptServiceImpl.SEPARATOR) + 1);
                byName.computeIfAbsent(name, k -> new ArrayList<>()).add(id);
            });
        }

        List<Long> resolve(ExcelImport<UserExcelRow> file, int row, String text) {
            Set<Long> ids = new LinkedHashSet<>();
            for (String token : split(text)) {
                String t = token.replaceAll("\\s*[/>＞]\\s*", DeptServiceImpl.SEPARATOR);
                if (t.contains(DeptServiceImpl.SEPARATOR)) {
                    Long id = byPath.get(t);
                    if (id == null) {
                        file.error(row, "所属部门", "部门树里没有「" + t + "」，先在用户页左边的部门树里建");
                    } else {
                        ids.add(id);
                    }
                    continue;
                }
                List<Long> found = byName.getOrDefault(t, List.of());
                if (found.isEmpty()) {
                    file.error(row, "所属部门", "部门树里没有「" + t + "」，先在用户页左边的部门树里建");
                } else if (found.size() > 1) {
                    file.error(row, "所属部门", "有 " + found.size() + " 个部门叫「" + t + "」，请写全路径，如 上级部门/" + t);
                } else {
                    ids.add(found.getFirst());
                }
            }
            return List.copyOf(ids);
        }
    }

    /** 角色：按名称找，也认编码 */
    static final class RoleMatcher {
        private final Map<String, Long> byName = new HashMap<>();
        private final List<String> names = new ArrayList<>();

        RoleMatcher(List<SysRole> roles) {
            for (SysRole role : roles) {
                byName.putIfAbsent(role.getName(), role.getId());
                byName.putIfAbsent(role.getCode(), role.getId());
                names.add(role.getName());
            }
        }

        List<Long> resolve(ExcelImport<UserExcelRow> file, int row, String text) {
            Set<Long> ids = new LinkedHashSet<>();
            for (String token : split(text)) {
                Long id = byName.get(token);
                if (id == null) {
                    file.error(row, "角色", "没有叫「" + token + "」的角色（现有：" + String.join("、", names) + "）");
                } else {
                    ids.add(id);
                }
            }
            return List.copyOf(ids);
        }
    }

    /** 品类线：认名字（服装）也认值（APPAREL）；空着三条都给（和 V44 给老账号的一样） */
    static final class LineMatcher {
        private final Map<String, String> byText = new HashMap<>();
        private final List<String> enabled = new ArrayList<>();

        LineMatcher(List<DictOption> options) {
            for (DictOption o : options) {
                if (o.status() != null && o.status() == 1) {
                    byText.put(o.label(), o.value());
                    byText.put(o.value().toUpperCase(Locale.ROOT), o.value());
                    enabled.add(o.value());
                }
            }
        }

        List<String> resolve(ExcelImport<UserExcelRow> file, int row, String text) {
            List<String> tokens = split(text);
            if (tokens.isEmpty()) {
                return List.copyOf(enabled);
            }
            Set<String> values = new LinkedHashSet<>();
            for (String token : tokens) {
                String value = byText.getOrDefault(token, byText.get(token.toUpperCase(Locale.ROOT)));
                if (value == null) {
                    file.error(row, "品类线", "品类线只能是 " + String.join("、", byText.entrySet().stream()
                            .filter(e -> !e.getKey().equals(e.getValue())).map(Map.Entry::getKey).sorted().toList()));
                } else {
                    values.add(value);
                }
            }
            // 按字典顺序存，和页面上保存的一样
            return enabled.stream().filter(values::contains).toList();
        }
    }
}
