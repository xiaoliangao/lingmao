package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.module.system.dto.DeptSaveReq;
import com.lingmao.scm.module.system.service.DeptService;
import com.lingmao.scm.module.system.service.UserImportService;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** 按领猫用户列表导账号，在真库上（RFC 018 §3）：建出来的人、部门、角色、品类线、要先改密码都对 */
@IntegrationTest
class UserImportIT {

    private static final long IMPORTER = 900801L;

    @Autowired
    private UserImportService userImportService;
    @Autowired
    private DeptService deptService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;

    /** 导入按钮是管理后台的（admin_only），只有超级管理员有：导入的人挂上 admin 角色 */
    @BeforeEach
    void login() {
        TestIdentity.ensureUser(jdbc, IMPORTER);
        jdbc.update("""
                INSERT INTO sys_user_role (user_id, role_id) SELECT ?, id FROM sys_role WHERE code = 'admin' AND NOT deleted
                ON CONFLICT DO NOTHING""", IMPORTER);
        TestIdentity.login(IMPORTER, List.of("system:user:import"));
    }

    @AfterEach
    void logout() {
        TestIdentity.clear();
    }

    /** 规则：领猫的中文登录名用手机号登录；部门按名字挂上（几个部门）；角色按名称；品类线认值；初始密码能登录、要先改 */
    @Test
    void lingmaoFormat_createsAccounts() throws IOException {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long parent = deptService.create(req(null, "商品" + tag));
        long design = deptService.create(req(parent, "设计" + tag));
        long buy = deptService.create(req(null, "采购" + tag));
        String role = jdbc.queryForObject("SELECT name FROM sys_role WHERE code = 'buyer' AND NOT deleted", String.class);
        String phone = "19" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));

        ImportResult result = userImportService.importExcel(file(
                new String[]{"小林-开发" + tag, "小林" + tag, phone, "女", "设计" + tag + "；采购" + tag, "设计师", role, "APPAREL", "启用", "7"}),
                "Init-" + tag);

        assertThat(result.total()).isEqualTo(1);
        Map<String, Object> user = jdbc.queryForMap("""
                SELECT id, nickname, pwd_must_change, product_lines::text AS lines, status, password, designer_no
                FROM sys_user WHERE username = ? AND NOT deleted""", phone);
        assertThat(user).containsEntry("nickname", "小林" + tag).containsEntry("pwd_must_change", true)
                .containsEntry("lines", "{APPAREL}").containsEntry("status", 1);
        assertThat(user.get("designer_no")).as("领猫的内部编码不当设计师编号").isNull();
        assertThat(passwordEncoder.matches("Init-" + tag, (String) user.get("password"))).isTrue();
        long id = ((Number) user.get("id")).longValue();
        assertThat(jdbc.queryForList("SELECT dept_id FROM sys_user_dept WHERE user_id = ? ORDER BY dept_id", Long.class, id))
                .containsExactly(design, buy);
        assertThat(jdbc.queryForList("SELECT r.code FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id WHERE ur.user_id = ?",
                String.class, id)).containsExactly("buyer");
    }

    private static DeptSaveReq req(Long parentId, String name) {
        DeptSaveReq req = new DeptSaveReq();
        req.setParentId(parentId);
        req.setName(name);
        req.setSort(0);
        return req;
    }

    /** 领猫导出的列，外加模板的角色、品类线 */
    private static MockMultipartFile file(String[]... rows) throws IOException {
        String[] headers = {"登录名", "昵称", "电话", "性别", "所属部门", "职位", "角色", "品类线", "启用", "内部编码"};
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = book.createSheet("用户");
            XSSFRow header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
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
}
