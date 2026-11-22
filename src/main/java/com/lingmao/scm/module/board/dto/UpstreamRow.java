package com.lingmao.scm.module.board.dto;
import com.lingmao.scm.module.biz.dto.DocNoticeVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import java.util.List;
/** 每单最多预览最新 100 条；操作只处理这些通知，随后重新读取。 */
public record UpstreamRow(DocRef doc, List<DocNoticeVO> notices, long noticesTotal) { }
