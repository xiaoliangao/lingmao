package com.lingmao.scm.module.supplier.service;

import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.module.supplier.dto.SupplierQuery;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * 供应商的导入导出（RFC 004 §9、数据库设计附录 A.1）。模板就是领猫导出的样子：
 * "供应商管理"一行一家，"银行收付款信息"可有可无；我们多出来的账期、税率、微信接在后面
 */
public interface SupplierExcelService {

    byte[] importTemplate();

    /**
     * 只新增，编码已存在算错误；有一处问题整批不导（1003）。
     * status 是导成什么状态：ACTIVE（默认，要 admit 权限，"是否冻结"为是的导成停用）或 PENDING_ADMIT。传别的 400
     */
    ImportResult importExcel(MultipartFile file, SupplierStatus status);

    /** 和列表一样的条件，一行一家；不出账号、户名、开户行 */
    byte[] export(SupplierQuery query);
}
