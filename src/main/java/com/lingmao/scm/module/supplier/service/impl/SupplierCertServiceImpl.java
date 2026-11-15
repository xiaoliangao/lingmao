package com.lingmao.scm.module.supplier.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.supplier.dto.CertSaveReq;
import com.lingmao.scm.module.supplier.dto.CertVO;
import com.lingmao.scm.module.supplier.entity.SupplierCert;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.mapper.SupplierCertMapper;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import com.lingmao.scm.module.supplier.service.SupplierCertService;
import com.lingmao.scm.module.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 资质证照（RFC 004 §2.2）。增删改之前先 FOR SHARE 锁住供应商行再看状态：
 * 申请准入是 FOR UPDATE 锁住再查营业执照，两边互斥，查的时候不会有人正在删它
 */
@Service
@RequiredArgsConstructor
public class SupplierCertServiceImpl implements SupplierCertService {

    private final SupplierCertMapper certMapper;
    private final SupplierMapper supplierMapper;
    private final DocLogService docLogService;
    private final UserService userService;
    private final SupplierDicts dicts;
    private final FileUrlSigner signer;

    @Override
    public List<CertVO> list(long supplierId) {
        if (supplierMapper.selectById(supplierId) == null) {
            throw new BizException(404, "供应商不存在，可能已经被删除了");
        }
        List<SupplierCert> certs = certMapper.selectList(new LambdaQueryWrapper<SupplierCert>()
                .eq(SupplierCert::getSupplierId, supplierId));
        Map<Long, String> names = new HashMap<>(userService.nicknames(certs.stream().map(SupplierCert::getCreateBy)
                .filter(Objects::nonNull).collect(Collectors.toSet())));
        return certs.stream()
                // 快到期的在前，长期有效的在后；同一天的按上传先后
                .sorted(Comparator.comparing(SupplierCert::getValidUntil, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SupplierCert::getId))
                .map(c -> new CertVO(c.getId(), c.getCertType(), signer.sign(c.getFileUrl()), c.getFileName(),
                        c.getValidUntil(), c.getRemark(), names.get(c.getCreateBy()), c.getCreateTime()))
                .toList();
    }

    @Override
    @Transactional
    public Long add(long supplierId, CertSaveReq req) {
        lockEditable(supplierId);
        dicts.check(SupplierDicts.CERT_TYPE, req.getCertType(), null, "证照类型");
        SupplierCert cert = new SupplierCert();
        cert.setSupplierId(supplierId);
        fill(cert, req);
        certMapper.insert(cert);
        docLogService.add(BizType.SUPPLIER, supplierId, "cert-add", describe(cert));
        return cert.getId();
    }

    @Override
    @Transactional
    public void update(long supplierId, long certId, CertSaveReq req) {
        lockEditable(supplierId);
        SupplierCert cert = getCert(supplierId, certId);
        dicts.check(SupplierDicts.CERT_TYPE, req.getCertType(), cert.getCertType(), "证照类型");
        fill(cert, req);
        certMapper.update(cert, Updates.replaceById(cert));
        docLogService.add(BizType.SUPPLIER, supplierId, "cert-edit", describe(cert));
    }

    @Override
    @Transactional
    public void delete(long supplierId, long certId) {
        lockEditable(supplierId);
        SupplierCert cert = getCert(supplierId, certId);
        // 只删记录，文件留着（RFC 001 §10）
        certMapper.deleteById(certId);
        docLogService.add(BizType.SUPPLIER, supplierId, "cert-remove", describe(cert));
    }

    /** FOR SHARE 锁住供应商行再看状态：准入审批中的不能动资质（1000） */
    private void lockEditable(long supplierId) {
        String status = supplierMapper.selectStatusForShare(supplierId);
        if (status == null) {
            throw new BizException(404, "供应商不存在，可能已经被删除了");
        }
        if (!SupplierRules.EDITABLE.contains(SupplierStatus.valueOf(status))) {
            throw new BizException("准入审批中不能改资质，先撤回");
        }
    }

    private SupplierCert getCert(long supplierId, long certId) {
        SupplierCert cert = certMapper.selectById(certId);
        if (cert == null || cert.getSupplierId() != supplierId) {
            throw new BizException(404, "这份证照不存在，可能已经被删除了");
        }
        return cert;
    }

    /** 请求放进实体。地址去掉签名、只收本系统上传的 /files/...（签名工具认不出的外部地址不存） */
    private void fill(SupplierCert cert, CertSaveReq req) {
        String url = signer.strip(req.getFileUrl().strip());
        if (!url.startsWith(FileUrlSigner.PREFIX) || url.length() > 255) {
            throw new BizException(400, "文件要先通过上传接口上传");
        }
        cert.setCertType(req.getCertType());
        cert.setFileUrl(url);
        cert.setFileName(req.getFileName().strip());
        cert.setValidUntil(req.getValidUntil());
        cert.setRemark(req.getRemark() == null || req.getRemark().isBlank() ? null : req.getRemark().strip());
    }

    /** 操作记录的备注："营业执照 执照.pdf，有效期至 2027-12-31" */
    private String describe(SupplierCert cert) {
        String until = cert.getValidUntil() == null ? "长期有效" : "有效期至 " + cert.getValidUntil();
        return dicts.labelOf(SupplierDicts.CERT_TYPE, cert.getCertType()) + " " + cert.getFileName() + "，" + until;
    }
}
