package com.lingmao.scm.module.techpack.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 工序图示（tp_process_image，数据库设计 §9.6）。工序行草稿保存时会换 id，挂不了 biz_attachment，跟着工序行一起整版覆盖。
 * 地址存不带签名的 /files/...，返回时签名
 */
@Data
@TableName("tp_process_image")
public class ProcessImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long processId;

    /** 顺序，从 0 起 */
    private Integer sort;

    private String fileUrl;

    private String thumbUrl;
}
