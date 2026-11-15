package com.lingmao.scm.module.biz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 附件和图片（biz_attachment，数据库设计 §4.2、§1.7）：挂在一条记录上的文件，款式图、物料图、报工照片、签回件都在这里，
 * 不再给每种图建表。文件本身已经由 /api/files 传好了，这里只记挂在哪、第几张、地址。
 * <p>
 * 同一条记录里一种页面控件一种 category：图片 IMAGE、文件 FILE；款式的设计附件 DESIGN、资料包工艺指示插图用段代码，各模块自己定。
 * IMAGE 里 sort 最小的是主图（PRD §5.8）。file_url、thumb_url 存不带签名的 /files/...，返回时由 AttachmentService 签名。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_attachment")
public class Attachment extends AuditEntity {

    /** 图片：第一张是主图 */
    public static final String IMAGE = "IMAGE";

    /** 普通文件 */
    public static final String FILE = "FILE";

    private BizType docType;
    private Long docId;
    private String category;

    /** 顺序，从 0 起；整组替换时按传进来的顺序重排 */
    private Integer sort;

    private String fileUrl;

    /** 缩略图，只有 jpg、png 有（文件名多一个 _s，RFC 001 §10）；没生成出来是空，前端用原图 */
    private String thumbUrl;

    /** 原始文件名 */
    private String fileName;

    /** 字节数 */
    private Long fileSize;
}
