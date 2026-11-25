package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.DirectFileImports;
import com.lingmao.scm.common.excel.ImportError;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.system.dto.DictOption;
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
import com.lingmao.scm.security.LineScope;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 按领猫用户列表导账号（RFC 018 §3） */
@ExtendWith(MockitoExtension.class)
class UserImportTest {

    /** 领猫导出的列（多出来的性别、内部编码认得、不导）加上模板的角色、品类线、设计师编号 */
    static final String[] HEADERS = {"登录名", "昵称", "电话", "性别", "所属部门", "职位", "角色", "品类线", "设计师编号", "启用", "内部编码"};

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private SysUserDeptMapper userDeptMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private DeptService deptService;
    @Mock
    private DictDataService dictDataService;
    @Mock
    private DefaultCompanyProvider companies;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserImportServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
    }

    @BeforeEach
    void setUp() {
        service = new UserImportServiceImpl(userMapper, userRoleMapper, userDeptMapper, roleMapper, deptService, dictDataService,
                companies, passwordEncoder, DirectFileImports.INSTANCE);
        lenient().when(roleMapper.selectList(isNull())).thenReturn(List.of(role(11, "开发", "developer"), role(12, "采购", "purchaser")));
        lenient().when(dictDataService.listByType(LineScope.DICT_TYPE)).thenReturn(List.of(new DictOption("服装", "APPAREL", 1),
                new DictOption("鞋类", "FOOTWEAR", 1), new DictOption("百货", "GENERAL", 1)));
    }

    /**
     * 规则：领猫的中文登录名用手机号代替；部门按名字或全路径找；设计师编号补两位；品类线空着三条都给；
     * 已有的登录名跳过不改。导进来的都要先改密码，密码只算一次哈希。跳过的、用手机号的、没角色的合成提示返回
     */
    @Test
    void importExcel_lingmaoList_createsAccounts() throws IOException {
        when(deptService.paths()).thenReturn(Map.of(1L, "产品部", 2L, "产品部/设计组", 3L, "产品部/版房", 4L, "采购部"));
        when(userMapper.selectList(any())).thenReturn(List.of(user("laozhang")));
        when(passwordEncoder.encode("Init-2026")).thenReturn("hashed");
        AtomicLong ids = new AtomicLong(100);
        doAnswer(inv -> {
            inv.<SysUser>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(userMapper).insert(any(SysUser.class));

        ImportResult result = service.importExcel(file(
                new String[]{"小林-开发", "小林", "138 0000 0001", "女", "设计组", "设计师", "开发", null, "3", null, "7"},
                new String[]{"Ling_Cg", "阿玲", null, null, "产品部 / 版房；采购部", "采购", null, "服装", null, "停用", null},
                new String[]{"laozhang", "老张", null, null, null, null, "采购", null, null, null, null}), "Init-2026");

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.notes()).extracting(ImportError::message).containsExactly(
                "第 4 行的登录名已经有了，跳过没导（没改那几个账号）",
                "第 2 行的登录名用手机号：表里的登录名空着，或者不是小写字母、数字、下划线",
                "第 3 行没填角色，登录后什么都看不到：在用户列表里勾上他们，批量加角色");
        ArgumentCaptor<SysUser> users = ArgumentCaptor.captor();
        verify(userMapper, times(2)).insert(users.capture());
        assertThat(users.getAllValues()).extracting(SysUser::getUsername, SysUser::getNickname, SysUser::getDesignerNo,
                        SysUser::getProductLines, SysUser::getStatus, SysUser::getPwdMustChange, SysUser::getPassword)
                .containsExactly(
                        tuple("13800000001", "小林", "03", List.of("APPAREL", "FOOTWEAR", "GENERAL"), 1, true, "hashed"),
                        tuple("ling_cg", "阿玲", null, List.of("APPAREL"), 0, true, "hashed"));
        verify(passwordEncoder, times(1)).encode(anyString());

        ArgumentCaptor<Collection<SysUserDept>> depts = ArgumentCaptor.captor();
        verify(userDeptMapper, times(2)).insert(depts.capture());
        assertThat(depts.getAllValues().get(1)).extracting(SysUserDept::getUserId, SysUserDept::getDeptId)
                .containsExactly(tuple(102L, 3L), tuple(102L, 4L));
        ArgumentCaptor<Collection<SysUserRole>> roles = ArgumentCaptor.captor();
        verify(userRoleMapper, times(1)).insert(roles.capture());
        assertThat(roles.getValue()).extracting(SysUserRole::getUserId, SysUserRole::getRoleId).containsExactly(tuple(101L, 11L));
    }

    /**
     * 规则：有问题整批不导（1003），逐行报：登录名和手机号都用不了、找不到的部门和角色、部门重名没写路径、
     * 设计师编号表里重复或者已经给过别人（删了的也算）、登录名表里重复
     */
    @Test
    void importExcel_problems_1003_nothingWritten() throws IOException {
        when(deptService.paths()).thenReturn(Map.of(2L, "产品部/设计组", 5L, "鞋部/设计组"));
        SysUser deleted = user("old");
        deleted.setDesignerNo("07");
        deleted.setNickname("老设计师");
        when(userMapper.selectDesignerNoOwners(any())).thenReturn(List.of(deleted));

        BizException e = catchThrowableOfType(BizException.class, () -> service.importExcel(file(
                new String[]{null, "甲", "12345", null, null, null, null, null, null, null, null},
                new String[]{"aaa", "乙", null, null, "组长", null, "老板", null, "07", null, null},
                new String[]{"bbb", "丙", null, null, "设计组", null, null, null, "8", null, null},
                new String[]{"ccc", "丁", null, null, null, null, null, null, "08", null, null},
                new String[]{"AAA", "戊", null, null, null, null, null, null, null, null, null}), "Init-2026"));

        assertThat(e.getCode()).isEqualTo(ErrorCodes.IMPORT_INVALID);
        @SuppressWarnings("unchecked")
        List<ImportError> errors = (List<ImportError>) e.getData();
        assertThat(errors).extracting(ImportError::row, ImportError::column).contains(
                tuple(2, "登录名"), tuple(3, "所属部门"), tuple(3, "角色"), tuple(3, "设计师编号"),
                tuple(4, "所属部门"), tuple(5, "设计师编号"), tuple(6, "登录名"));
        assertThat(errors).filteredOn(err -> err.row() == 4).extracting(ImportError::message)
                .containsExactly("有 2 个部门叫「设计组」，请写全路径，如 上级部门/设计组");
        assertThat(errors).filteredOn(err -> err.row() == 3 && "设计师编号".equals(err.column())).extracting(ImportError::message)
                .containsExactly("设计师编号 07 已经给了老设计师（编号不回收，删了的账号也算）");
        verify(userMapper, never()).insert(any(SysUser.class));
        verify(passwordEncoder, never()).encode(any());
    }

    /** 规则：初始密码 8-32 位，不对的连文件都不读 */
    @Test
    void importExcel_shortPassword_rejectedBeforeReading() {
        assertThatThrownBy(() -> service.importExcel(new MockMultipartFile("file", new byte[0]), "1234567"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(400))
                .hasMessage("初始密码要 8-32 位");
        verify(deptService, never()).paths();
    }

    private static MockMultipartFile file(String[]... rows) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = book.createSheet("用户");
            XSSFRow header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }
            for (int r = 0; r < rows.length; r++) {
                XSSFRow row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            book.write(out);
            return new MockMultipartFile("file", "用户.xlsx", "application/octet-stream", out.toByteArray());
        }
    }

    private static SysUser user(String username) {
        SysUser u = new SysUser();
        u.setUsername(username);
        return u;
    }

    private static SysRole role(long id, String name, String code) {
        SysRole r = new SysRole();
        r.setId(id);
        r.setName(name);
        r.setCode(code);
        return r;
    }
}
