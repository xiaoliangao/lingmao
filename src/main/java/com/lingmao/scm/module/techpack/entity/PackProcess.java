package com.lingmao.scm.module.techpack.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 工序工价的一行（tp_process，数据库设计 §9.5），在工艺指示页签最下面，标"内部"。合计就是参考加工价，核价按外发算时的加工费。
 * 不叫 Process：和 java.lang.Process 重名
 */
@Data
@TableName("tp_process")
public class PackProcess {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long techPackId;

    private Integer seq;

    /** 工序类别，字典 process_category */
    private String category;

    private String name;

    /** 工艺要求 */
    private String requirement;

    /** 标准工时，秒 */
    private Integer stdSeconds;

    /** 工价，元 / 件 */
    private BigDecimal price;

    private String remark;
}
