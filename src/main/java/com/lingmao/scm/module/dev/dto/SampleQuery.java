package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.biz.dto.TaggedPageQuery;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDate;
@Data @EqualsAndHashCode(callSuper=true)
public class SampleQuery extends TaggedPageQuery {
    public enum Sort { CREATED_DESC,CREATED_ASC,NEED_ASC,NEED_DESC,CODE_ASC,CODE_DESC }
    @NotNull(message="请选择主体公司") @Positive(message="主体公司编号不合法") private Long companyId;
    @Size(max=10099,message="查询词过长") private String keyword;
    @Pattern(regexp="^(WAIT_ASSIGN|MAKING|WAIT_REVIEW|PASSED|CONVERTED|ELIMINATED)?$",message="样衣状态不合法") private String status;
    @Positive(message="品类编号不合法") private Long categoryId;
    @Min(value=2000,message="年份须在2000–2099") @Max(value=2099,message="年份须在2000–2099") private Integer year;
    @Size(max=64,message="季节最长64个字") private String season;
    @Size(max=64,message="品牌最长64个字") private String brand;
    @Positive(message="设计师编号不合法") private Long designerId;
    @Positive(message="打样工厂编号不合法") private Long factoryId;
    @Positive(message="打样员编号不合法") private Long makerId;
    @Size(max=64,message="节点编码最长64个字") private String nodeCode;
    private boolean late;
    private LocalDate needFrom,needTo,createdFrom,createdTo;
    private Sort sort=Sort.CREATED_DESC;
}
