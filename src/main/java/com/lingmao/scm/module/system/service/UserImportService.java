package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.excel.ImportResult;
import org.springframework.web.multipart.MultipartFile;

/** 账号导入（RFC 018 §3）：拿领猫导出的用户表、或者我们的模板，一次建一批账号 */
public interface UserImportService {

    /** 导入模板：领猫的列名，加上角色、品类线、设计师编号、默认主体 */
    byte[] template();

    /**
     * 导入。password 是这一批人共用的初始密码，每个人第一次登录必须改（RFC 018 §2）。
     * 有一行不对整批不导（1003）；登录名已经有了的跳过、进提示
     */
    ImportResult importExcel(MultipartFile file, String password);
}
