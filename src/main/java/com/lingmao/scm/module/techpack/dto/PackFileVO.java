package com.lingmao.scm.module.techpack.dto;

/**
 * 工艺单页签的一个文件（前端 TechPackFile，docs/api/01 §3.3）。url、thumbUrl 带签名；id 用来调预览
 * （GET /api/tech-packs/{id}/files/{fileId}/preview）。category 见 PackFileForm
 */
public record PackFileVO(Long id, String category, String url, String thumbUrl, String name, Long size) {
}
