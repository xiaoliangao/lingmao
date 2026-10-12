package com.lingmao.scm.module.system.dto;

/** 上传成功返回：访问地址、原文件名、大小（字节） */
public record FileVO(String url, String name, long size) {
}
