package com.lingmao.scm.module.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 整组替换附件时的一个文件。四个字段和上传接口返回的 FileVO 同名：前端 ImageUpload 的 v-model 就是 FileVO 数组，
 * 原样传回来就行，地址带着签名也没关系，存之前去掉（AttachmentService.replace）。
 * <p>
 * 缩略图地址由前端传，不由后端按 _s 的命名规则推：只有上传那一刻知道缩略图生成出来没有（坏图、超大图不生成，pdf 没有），
 * 推出来的地址可能指向一个不存在的文件；表里本来也存了 thumb_url 这一列
 */
@Data
public class AttachmentReq {

    @NotBlank(message = "文件地址不能为空")
    private String url;

    private String thumbUrl;

    @NotBlank(message = "文件名不能为空")
    @Size(max = 255, message = "文件名最长 255 个字")
    private String name;

    /** 字节数 */
    private Long size;
}
