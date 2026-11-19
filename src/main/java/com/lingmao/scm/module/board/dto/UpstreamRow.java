package com.lingmao.scm.module.board.dto;

import com.lingmao.scm.module.biz.dto.DocNoticeVO;
import com.lingmao.scm.module.biz.dto.DocRef;

import java.util.List;

/** 上游已变更的一行：一张下游单据（负责人在 doc.ownerName）和它没处理的提醒，新的在前 */
public record UpstreamRow(DocRef doc, List<DocNoticeVO> notices) {
}
