package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import java.util.List;
public record SampleImagesResult(Long id,Long companyId,int version,List<AttachmentVO> files) { }
