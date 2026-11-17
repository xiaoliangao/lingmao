package com.lingmao.scm.module.techpack.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 一个版本：单头 + 尺寸指示、物料清单、工艺指示（前端 TechPackDetail，docs/api/01 §3.3）。核价另取 */
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
    private Long sourcePackId;
    private Integer sourceVersion;
    private BigDecimal utilPct;
    private String packingMode;
    private Long publishBy;
    private String publishByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime publishTime;
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;
    /** 乐观锁：保存、生效都带回来 */
    private Integer versionLock;
    /** LIVE 草稿，SNAPSHOT 新生效版，LEGACY 无历史上下文快照的旧版。 */
    private String contextSource;
    private String contextNotice;
    /** 款式尺码组的尺码，按顺序 */
    private List<String> sizes;
    private String baseSize;
    /** 展示上下文的有序颜色；生效后有快照的版本不跟随当前主数据。 */
    private List<ColorOption> colors;
    private List<PointOption> pointOptions;
    private List<SpecPointVO> specPoints;
    private List<BomItemVO> bomItems;
    private List<CraftSectionVO> craftSections;
    private List<ProcessVO> processes;
    /** 工序工价合计：总工时（秒）、参考加工价（元 / 件） */
    private Integer stdSecondsTotal;
    private BigDecimal laborTotal;
    /** 单件成本（核价合计，按第一个颜色）；没有看成本权限是 null */
    private BigDecimal unitCost;
    /** edit、remove、publish、revise、toProd（docs/api/01 §2） */
    private List<String> actions;
}
