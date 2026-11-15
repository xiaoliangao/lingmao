package com.lingmao.scm.module.system.dto;

/**
 * 上传成功返回：访问地址、缩略图地址、原文件名、大小（字节）。
 * thumbUrl 只有 jpg、png 有（长边 320px，文件名比原图多一个 _s）；别的类型、或者缩略图没生成出来，是 null，前端就用原图
 */
public record FileVO(String url, String thumbUrl, String name, long size) {
}
