package com.lingmao.scm.module.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.mapper.AttachmentMapper;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl implements AttachmentService {

    /** file_url、thumb_url 列的长度 */
    private static final int MAX_URL = 255;

    private final AttachmentMapper attachmentMapper;
    private final FileUrlSigner signer;

    @Override
    public List<AttachmentVO> list(BizType type, long docId, String category) {
        return attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                        .eq(Attachment::getDocType, type)
                        .eq(Attachment::getDocId, docId)
                        .eq(Attachment::getCategory, category)
                        .orderByAsc(Attachment::getSort, Attachment::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public Map<Long, List<AttachmentVO>> lists(BizType type, Collection<Long> docIds, String category) {
        if (docIds == null || docIds.isEmpty()) {
            return Map.of();
        }
        return attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                        .eq(Attachment::getDocType, type)
                        .in(Attachment::getDocId, docIds)
                        .eq(Attachment::getCategory, category)
                        .orderByAsc(Attachment::getDocId, Attachment::getSort, Attachment::getId))
                .stream().collect(Collectors.groupingBy(Attachment::getDocId, Collectors.mapping(this::toVO, Collectors.toList())));
    }

    @Override
    public Map<Long, AttachmentVO> firsts(BizType type, Collection<Long> docIds, String category) {
        if (docIds == null || docIds.isEmpty()) {
            return Map.of();
        }
        return attachmentMapper.selectFirsts(type, docIds, category).stream()
                .collect(Collectors.toMap(Attachment::getDocId, this::toVO));
    }

    @Override
    @Transactional
    public List<AttachmentVO> replace(BizType type, long docId, String category, List<AttachmentReq> files) {
        // 先全部检查完再动库：有一个不对就整组拒收，原来的一张都不少
        List<Attachment> rows = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            rows.add(toRow(type, docId, category, i, files.get(i)));
        }
        attachmentMapper.lockDoc("biz_attachment:" + type.name() + ":" + docId);
        attachmentMapper.delete(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getDocType, type)
                .eq(Attachment::getDocId, docId)
                .eq(Attachment::getCategory, category));
        if (!rows.isEmpty()) {
            attachmentMapper.insert(rows);
        }
        return list(type, docId, category);
    }

    @Override
    public void deleteByDoc(BizType type, long docId) {
        attachmentMapper.delete(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getDocType, type)
                .eq(Attachment::getDocId, docId));
    }

    /** 一个文件 → 一行。sort 就是它在列表里的位置，0 排最前：图片的主图 */
    private Attachment toRow(BizType type, long docId, String category, int sort, AttachmentReq file) {
        Attachment row = new Attachment();
        row.setDocType(type);
        row.setDocId(docId);
        row.setCategory(category);
        row.setSort(sort);
        row.setFileUrl(ownFile(file.getUrl()));
        row.setThumbUrl(StringUtils.hasText(file.getThumbUrl()) ? ownFile(file.getThumbUrl()) : null);
        if (!StringUtils.hasText(file.getName())) {
            throw new BizException(400, "第 " + (sort + 1) + " 个文件没有文件名");
        }
        row.setFileName(file.getName());
        row.setFileSize(file.getSize());
        return row;
    }

    /**
     * 去掉签名，还原成库里存的 /files/...（前端把拿到的签名地址原样传回来）。只收本系统上传的文件：
     * 外部地址存进来，签名、Nginx 校验都管不到它，页面上就成了一张随便指向哪里的图；带 .. 的也不收
     */
    private String ownFile(String url) {
        String path = signer.strip(url);
        if (path == null || !path.startsWith(FileUrlSigner.PREFIX) || path.contains("..") || path.length() > MAX_URL) {
            throw new BizException(400, "只能用本系统上传的文件，这个地址不行：" + url);
        }
        return path;
    }

    @Override
    public void copy(BizType type, long fromDocId, long toDocId) {
        attachmentMapper.copy(type, fromDocId, toDocId, LoginUser.currentUserId());
    }

    private AttachmentVO toVO(Attachment row) {
        return new AttachmentVO(row.getId(), signer.sign(row.getFileUrl()), signer.sign(row.getThumbUrl()),
                row.getFileName(), row.getFileSize());
    }
}
