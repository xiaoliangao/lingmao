package com.lingmao.scm.module.board.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocRef;

/**
 * 待办的一条（docs/api/06 §2.3）：一张单据，或者一个供应商（证照、准入）。
 *
 * @param ref   点了去哪（前端 docRoute）：异常是 PRODUCTION_ISSUE、parentId 是生产单；证照、准入是 SUPPLIER
 * @param title 第一行，拼好的："需求订单 DD2611150001""卡其布 还差 120 米"
 * @param sub   第二行，拼好的
 * @param image 款式图或物料图；没有是 null
 */
public record TodoItem(DocRef ref, String title, String sub, AttachmentVO image) {
}
