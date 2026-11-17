package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.system.dto.FileVO;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 工序工价的一行（前端 ProcessRow）。图示给 FileVO：name 是从地址里取的文件名，size 是 0，ImageUpload 直接能用 */
@Data
public class ProcessVO {
    private Long id;
    private Integer seq;
    private String category;
    private String name;
    private String requirement;
    private Integer stdSeconds;
    private BigDecimal price;
    private String remark;
    private List<FileVO> images;
}
