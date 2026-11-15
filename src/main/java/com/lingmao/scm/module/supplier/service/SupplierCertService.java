package com.lingmao.scm.module.supplier.service;

import com.lingmao.scm.module.supplier.dto.CertSaveReq;
import com.lingmao.scm.module.supplier.dto.CertVO;

import java.util.List;

/**
 * 供应商的资质证照（RFC 004 §2.2）。准入审批中不能增删改（1000）：先锁住供应商行（FOR SHARE）再看状态，
 * 和申请准入（FOR UPDATE）互斥，申请准入检查营业执照的时候不会有人正在删它。
 * 文件地址存 /files/...，返回时签名；删证照只删记录，文件不删（RFC 001 §10）
 */
public interface SupplierCertService {

    /** 按有效期排，长期有效的在后 */
    List<CertVO> list(long supplierId);

    Long add(long supplierId, CertSaveReq req);

    void update(long supplierId, long certId, CertSaveReq req);

    void delete(long supplierId, long certId);
}
