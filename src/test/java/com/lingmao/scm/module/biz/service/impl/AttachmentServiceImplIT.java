package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 附件在真库上（V9、RFC 003 §2）：整组替换后的顺序、旧的没了、按类型 + id 一起删、同时替换不叠加。每个测试用随机的 doc_id */
@IntegrationTest
class AttachmentServiceImplIT {

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private FileUrlSigner signer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 规则：第二次替换以后，查出来就是第二次给的顺序，第一次有、第二次没有的不见了；库里存的是去掉签名的地址，sort 从 0 起。
     * 第二次传的是第一次返回的签名地址（前端把拿到的原样传回来），存得进去
     */
    @Test
    void replace_twice_newOrderAndOldGone() {
        long docId = randomId();
        List<AttachmentVO> first = attachmentService.replace(BizType.STYLE, docId, Attachment.IMAGE,
                List.of(file("a", true), file("b", true), file("c", false)));
        assertThat(first).extracting(AttachmentVO::name).containsExactly("a.jpg", "b.jpg", "c.jpg");

        attachmentService.replace(BizType.STYLE, docId, Attachment.IMAGE, List.of(back(first.get(2)), back(first.get(0))));

        List<AttachmentVO> list = attachmentService.list(BizType.STYLE, docId, Attachment.IMAGE);
        assertThat(list).extracting(AttachmentVO::name).containsExactly("c.jpg", "a.jpg");
        assertThat(list.get(1).thumbUrl()).isEqualTo(signer.sign(path("a_s")));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT file_url, sort FROM biz_attachment WHERE doc_type = 'STYLE' AND doc_id = ? ORDER BY sort", docId);
        assertThat(rows).extracting(row -> row.get("file_url")).containsExactly(path("c"), path("a"));
        assertThat(rows).extracting(row -> row.get("sort")).containsExactly(0, 1);
        assertThat(attachmentService.firsts(BizType.STYLE, List.of(docId), Attachment.IMAGE).get(docId).name())
                .isEqualTo("c.jpg");
    }

    /** 规则：按类型 + id 删掉这条记录所有分类的附件；别的记录、别的类型同一个 id 的不动 */
    @Test
    void deleteByDoc_onlyThisDoc() {
        long docId = randomId();
        long otherId = randomId();
        attachmentService.replace(BizType.STYLE, docId, Attachment.IMAGE, List.of(file("a", true)));
        attachmentService.replace(BizType.STYLE, docId, "DESIGN", List.of(file("b", false)));
        attachmentService.replace(BizType.STYLE, otherId, Attachment.IMAGE, List.of(file("c", true)));
        attachmentService.replace(BizType.MATERIAL, docId, Attachment.IMAGE, List.of(file("d", true)));

        attachmentService.deleteByDoc(BizType.STYLE, docId);

        assertThat(count(BizType.STYLE, docId)).isZero();
        assertThat(count(BizType.STYLE, otherId)).isEqualTo(1);
        assertThat(count(BizType.MATERIAL, docId)).isEqualTo(1);
    }

    /**
     * 规则：几个人同时替换同一条记录的图，留下的正好是其中一个人的那一组，不会叠在一起。
     * 没有 advisory lock 时，各自"先删后插"，DELETE 看不见别人还没提交的新行，最后会剩下好几组
     */
    @Test
    void replace_concurrent_oneGroupWins() throws Exception {
        long docId = randomId();
        int threads = 6;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String group = "t" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return attachmentService.replace(BizType.STYLE, docId, Attachment.IMAGE,
                            IntStream.range(0, 3).mapToObj(k -> file(group + "-" + k, false)).toList());
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }

            List<AttachmentVO> list = attachmentService.list(BizType.STYLE, docId, Attachment.IMAGE);
            assertThat(list).hasSize(3);
            String winner = list.getFirst().name().substring(0, 2);
            assertThat(list).extracting(AttachmentVO::name)
                    .containsExactly(winner + "-0.jpg", winner + "-1.jpg", winner + "-2.jpg");
        } finally {
            pool.shutdownNow();
        }
    }

    private Integer count(BizType type, long docId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM biz_attachment WHERE doc_type = ? AND doc_id = ?",
                Integer.class, type.name(), docId);
    }

    /** 上传接口返回的样子：地址带签名；有缩略图的带 _s 那张 */
    private AttachmentReq file(String name, boolean thumb) {
        AttachmentReq req = new AttachmentReq();
        req.setUrl(signer.sign(path(name)));
        req.setThumbUrl(thumb ? signer.sign(path(name + "_s")) : null);
        req.setName(name + ".jpg");
        req.setSize(2048L);
        return req;
    }

    /** 查出来的附件原样传回去 */
    private static AttachmentReq back(AttachmentVO vo) {
        AttachmentReq req = new AttachmentReq();
        req.setUrl(vo.url());
        req.setThumbUrl(vo.thumbUrl());
        req.setName(vo.name());
        req.setSize(vo.size());
        return req;
    }

    private static String path(String name) {
        return "/files/2026/11/15/" + name + ".jpg";
    }

    private static long randomId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }
}
