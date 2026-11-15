package com.lingmao.scm.module.style.dto;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 新建款式：款式自己的列 + 款号怎么来 + 第一批颜色、图片、设计附件（RFC 005 §7）。
 * 带了颜色的，建款时就按 颜色 × 尺码组 生成 SKU（和原型一样，建完就能看到 SKU）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StyleCreateReq extends StyleSaveReq {

    /** 款号怎么来 */
    public enum CodeMode {
        /** 大类 + yyMMdd + 设计师编号 + 2 位流水，保存时才取号（RFC 005 §3.1） */
        AUTO,
        /** 手填：转大写，3-20 位字母、数字、横杠（RFC 005 §3.2） */
        MANUAL
    }

    @NotNull(message = "请选择款号是自动编号还是手填")
    private CodeMode codeMode;

    /** 手填的款号；自动编号时不看 */
    @Size(max = 32, message = "款号最长 32 位")
    private String code;

    @Size(max = 100, message = "一次最多选 100 个颜色")
    private List<Long> colorIds = new ArrayList<>();

    @Size(max = 20, message = "最多 20 张图片")
    private List<@Valid AttachmentReq> images = new ArrayList<>();

    @Size(max = 20, message = "最多 20 个附件")
    private List<@Valid AttachmentReq> files = new ArrayList<>();
}
