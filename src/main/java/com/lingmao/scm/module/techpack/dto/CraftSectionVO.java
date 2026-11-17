package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.techpack.entity.CraftSection;

import java.util.List;

/** 工艺指示的一段（前端 CraftSectionView）。九段都返回，没写的 content 是 '' */
public record CraftSectionVO(CraftSection section, String content, List<AttachmentVO> images) {
}
