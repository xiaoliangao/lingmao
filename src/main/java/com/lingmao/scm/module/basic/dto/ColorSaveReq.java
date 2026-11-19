package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建、修改颜色。编码建好不改：修改时传的编码要和原来一样（大小写不算）。
 * 这里放宽到 2-3 位，是因为从领猫导进来的老颜色可能是 3 位、带数字，修改时照样要传；新建只收 2 位字母，在 Service 里查（甲方 11-18，RFC 012 §5.3）
 */
@Data
public class ColorSaveReq {

    @NotBlank(message = "编码不能为空")
    @Pattern(regexp = "^\\s*[A-Za-z0-9]{2,3}\\s*$", message = "颜色编码是 2-3 位字母或数字")
    private String code;

    @NotBlank(message = "名称不能为空")
    @Size(max = 32, message = "名称最长 32 个字")
    private String name;

    /** 色系，字典 color_family */
    @Size(max = 64, message = "色系最长 64 个字")
    private String family;

    /** 色块，#RRGGBB */
    @Pattern(regexp = "^$|^#[0-9A-Fa-f]{6}$", message = "色块是 #RRGGBB 这样的颜色值")
    private String hex;
}
