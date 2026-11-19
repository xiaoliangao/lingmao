package com.lingmao.scm.module.techpack.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 一个版本：单头 + 物料清单 + 工艺单文件（前端 TechPackDetail，docs/api/01 §3.3）。核价另取 */
@Data
public class TechPackDetailVO {
    private Long id;
    private Long styleId;
    private String styleCode;
    private String styleName;
    private String aliasName;
    private PackStage stage;
    private Integer version;
    private PackStatus status;
    private String versionNote;
    private BigDecimal utilPct;
    private Long publishBy;
    private String publishByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime publishTime;
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;
    /** 乐观锁：保存、生效都带回来 */
    private Integer versionLock;
    /** 颜色尺码取自哪里：LIVE 草稿取款式现在的，SNAPSHOT 生效时记下的，LEGACY 升级前生效的老版本（没记，取现在的加上 BOM 里用到的） */
    private String contextSource;
    /** 款式尺码组的尺码，按顺序 */
    private List<String> sizes;
    private String baseSize;
    /** 展示上下文的有序颜色；生效后有快照的版本不跟随当前主数据。 */
    private List<ColorOption> colors;
    private List<BomItemVO> bomItems;
    /** 工艺单页签的文件：按组（工艺单、纸样和其他、导入的 BOM 表），组内按顺序 */
    private List<PackFileVO> files;
    /** 单件成本（核价合计，按第一个颜色）；没有看成本权限是 null */
    private BigDecimal unitCost;
    /** edit、remove、publish、revise（docs/api/01 §2） */
    private List<String> actions;
}
