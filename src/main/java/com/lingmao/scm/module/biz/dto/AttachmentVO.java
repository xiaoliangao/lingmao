package com.lingmao.scm.module.biz.dto;

/**
 * 返回给前端的一个附件。url、thumbUrl、name、size 和上传接口的 FileVO 同名，ImageUpload 直接能用；
 * 两个地址都带签名（FileUrlSigner），几天后过期，刷新页面重新取。thumbUrl 为空时前端用原图
 */
public record AttachmentVO(Long id, String url, String thumbUrl, String name, Long size) {
}
