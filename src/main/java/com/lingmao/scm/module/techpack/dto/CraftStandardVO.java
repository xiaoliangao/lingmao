package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.techpack.entity.CraftSection;

import java.util.List;
import java.util.Map;

/**
 * 工艺标准的全部内容（GET /api/craft-standards，前端 CraftStandard）
 *
 * @param files       工艺标准文件，按顺序；导出 PDF 附在最后，name 是附页标题
 * @param texts       九段的默认文字，都有，没写是 ''
 * @param packingMode 新建资料包时默认的包装方式；没设是 null
 * @param editable    有没有 techpack:standard:edit
 */
public record CraftStandardVO(List<AttachmentVO> files, Map<CraftSection, String> texts, String packingMode,
                              List<SizePointStd> points, boolean editable) {

    /** 字典 size_point 的一项和它的默认测量方法；停用的也列出来 */
    public record SizePointStd(String value, String label, String method, Integer status) {
    }
}
