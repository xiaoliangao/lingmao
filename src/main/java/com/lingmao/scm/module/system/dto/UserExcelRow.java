package com.lingmao.scm.module.system.dto;

import lombok.Data;

/**
 * 账号导入的一行（RFC 018 §3.2）。列名照领猫"用户管理"导出的叫法，领猫的导出直接能导；
 * 角色、品类线、设计师编号、默认主体是领猫没有的，甲方补上。格子里都是原样的文字，Service 再翻
 */
@Data
public class UserExcelRow {
    private String username;
    private String nickname;
    private String phone;
    private String depts;
    private String post;
    private String roles;
    private String productLines;
    private String designerNo;
    private String company;
    private String enabled;
}
