package com.lingmao.scm.module.dev.dto;
public record SampleConvertPreview(String code,String codeError,int skuCount,DevPack devPack) {
    public record DevPack(Long id,int version,String status) { }
}
