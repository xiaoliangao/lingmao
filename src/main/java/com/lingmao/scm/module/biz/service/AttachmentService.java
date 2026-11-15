package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.BizType;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 附件和图片（架构 §4.4、数据库设计 §4.2）：挂在一条记录上，按 category 分组（Attachment.IMAGE、FILE……），组内有顺序，图片第一张是主图。
 * 返回的地址都已经签过名（FileUrlSigner）；库里存的是不带签名的 /files/...。
 * 上传的文件只增不删（RFC 001 §10）：这里删的只是引用，文件还在。
 * <p>
 * 不单独开接口：各资源的 controller 挂自己的 PUT /api/&lt;资源&gt;/{id}/images 之类（RFC 003 §2），权限跟着那条记录走。
 */
public interface AttachmentService {

    /** 一条记录某个分类下的全部，按顺序 */
    List<AttachmentVO> list(BizType type, long docId, String category);

    /**
     * 列表页的主图：每条记录在这个分类下排第一的那个，docId → 附件；没有图的不在 map 里。
     * 一页列表只查一次，不用每行查一遍
     */
    Map<Long, AttachmentVO> firsts(BizType type, Collection<Long> docIds, String category);

    /**
     * 整组替换（RFC 003 §2）：这个分类下原来的全删掉，按 files 的顺序重新插入，第一个排最前（图片就是主图）。传空列表就是清空。
     * 地址去掉签名再存；不是本系统上传的（不以 /files/ 开头）整组拒收，返回 400，原来的不动。
     * 同一条记录的替换排队做，两个人同时保存，留下的是后保存的那一组，不会叠在一起。返回替换后的这一组
     */
    List<AttachmentVO> replace(BizType type, long docId, String category, List<AttachmentReq> files);

    /** 删一条记录时，同一个事务里把它所有分类的附件一起删（RFC 003 §2） */
    void deleteByDoc(BizType type, long docId);
}
