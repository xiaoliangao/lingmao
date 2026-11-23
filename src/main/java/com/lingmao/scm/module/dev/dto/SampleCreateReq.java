package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
@Data @EqualsAndHashCode(callSuper=true)
public class SampleCreateReq extends SampleFields {
    @NotNull(message="请选择首版要样日期") private LocalDate needDate;
    @NotNull(message="图片列表不能为空") @Size(max=20,message="最多20张样衣图片") private List<@Valid AttachmentReq> images=new ArrayList<>();
}
