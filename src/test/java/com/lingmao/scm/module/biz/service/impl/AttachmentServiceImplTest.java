package com.lingmao.scm.module.biz.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.mapper.AttachmentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 附件（RFC 003 §2）：整组替换的顺序和主图、外部地址拒收、返回的地址带签名 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceImplTest {

    private static final String IMAGE_B = "/files/2026/11/15/b.jpg";
    private static final String THUMB_B = "/files/2026/11/15/b_s.jpg";
    private static final String FILE_A = "/files/2026/11/15/a.pdf";

    private final FileUrlSigner signer = new FileUrlSigner("test-file-sign-key-0123456789abcdef",
            Clock.fixed(Instant.parse("2026-11-15T02:00:00Z"), ZoneId.of("Asia/Shanghai")));

    @Mock
    private AttachmentMapper attachmentMapper;

    private AttachmentServiceImpl service;

    /** 删、查用了 Lambda 条件，单元测试不起 Spring，手动建表信息（见 RoleServiceImplTest） */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Attachment.class);
    }

    @BeforeEach
    void setUp() {
        service = new AttachmentServiceImpl(attachmentMapper, signer);
    }

    /**
     * 规则：整组替换按传进来的顺序存，sort 从 0 起，第一个就是主图；前端传回来的签名地址去掉签名再存；
     * 先锁这条记录、再删这一组、再插新的
     */
    @Test
    @SuppressWarnings("unchecked")
    void replace_keepsOrderFirstIsMain_storesUnsignedUrls() {
        List<AttachmentReq> files = List.of(
                req(signer.sign(IMAGE_B), signer.sign(THUMB_B), "b.jpg"),
                req(FILE_A, null, "a.pdf"));

        service.replace(BizType.STYLE, 9L, Attachment.IMAGE, files);

        ArgumentCaptor<Collection<Attachment>> inserted = ArgumentCaptor.captor();
        InOrder order = inOrder(attachmentMapper);
        order.verify(attachmentMapper).lockDoc("biz_attachment:STYLE:9");
        order.verify(attachmentMapper).delete(any());
        order.verify(attachmentMapper).insert(inserted.capture());
        List<Attachment> rows = List.copyOf(inserted.getValue());
        assertThat(rows).extracting(Attachment::getSort).containsExactly(0, 1);
        assertThat(rows).extracting(Attachment::getFileUrl).containsExactly(IMAGE_B, FILE_A);
        assertThat(rows).extracting(Attachment::getThumbUrl).containsExactly(THUMB_B, null);
        assertThat(rows).extracting(Attachment::getFileName).containsExactly("b.jpg", "a.pdf");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getDocType()).isEqualTo(BizType.STYLE);
            assertThat(row.getDocId()).isEqualTo(9L);
            assertThat(row.getCategory()).isEqualTo(Attachment.IMAGE);
        });
    }

    /** 规则：有一个不是本系统上传的地址（外部链接、缩略图是外部的、带 ..），整组拒收 400，原来的一个都不删 */
    @Test
    void replace_notOwnFile_rejected400_nothingTouched() {
        List<List<AttachmentReq>> bad = List.of(
                List.of(req(IMAGE_B, null, "b.jpg"), req("https://example.com/x.jpg", null, "x.jpg")),
                List.of(req(IMAGE_B, "https://example.com/x_s.jpg", "b.jpg")),
                List.of(req("/files/../application.yaml", null, "x")));

        for (List<AttachmentReq> files : bad) {
            assertThatThrownBy(() -> service.replace(BizType.STYLE, 9L, Attachment.IMAGE, files))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("只能用本系统上传的文件")
                    .extracting("code").isEqualTo(400);
        }
        verify(attachmentMapper, never()).lockDoc(anyString());
        verify(attachmentMapper, never()).delete(any());
        verify(attachmentMapper, never()).insert(anyCollection());
    }

    /** 规则：返回的地址都带签名，去掉签名就是库里存的那个；没有缩略图的还是空 */
    @Test
    void list_returnsSignedUrls() {
        when(attachmentMapper.selectList(any())).thenReturn(List.of(row(IMAGE_B, THUMB_B), row(FILE_A, null)));

        List<AttachmentVO> list = service.list(BizType.STYLE, 9L, Attachment.IMAGE);

        assertThat(list).extracting(AttachmentVO::url).allSatisfy(url -> assertThat(url).contains("?e=").contains("&s="));
        assertThat(list).extracting(vo -> signer.strip(vo.url())).containsExactly(IMAGE_B, FILE_A);
        assertThat(list.get(0).thumbUrl()).isEqualTo(signer.sign(THUMB_B));
        assertThat(list.get(1).thumbUrl()).isNull();
    }

    /** 规则：列表这一页一条都没有时不查库（IN () 是错的 SQL） */
    @Test
    void firsts_noIds_noQuery() {
        assertThat(service.firsts(BizType.STYLE, List.of(), Attachment.IMAGE)).isEmpty();
        verify(attachmentMapper, never()).selectFirsts(any(), any(), any());
    }

    private static AttachmentReq req(String url, String thumbUrl, String name) {
        AttachmentReq req = new AttachmentReq();
        req.setUrl(url);
        req.setThumbUrl(thumbUrl);
        req.setName(name);
        req.setSize(1024L);
        return req;
    }

    private static Attachment row(String url, String thumbUrl) {
        Attachment row = new Attachment();
        row.setFileUrl(url);
        row.setThumbUrl(thumbUrl);
        row.setFileName("x");
        return row;
    }
}
