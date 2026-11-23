package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.dev.entity.SampleReviewResult;
import java.time.LocalDate;
import java.util.List;
public record SampleReviewVO(Long id,LocalDate reviewDate,SampleReviewResult result,String opinion,List<Person> reviewers,List<AttachmentVO> photos) {
    public record Person(Long id,String name) { }
}
