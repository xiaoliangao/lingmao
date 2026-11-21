package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.inventory.entity.CountPurpose;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 盘点单列表（docs/api/04 §3.5.1）；导出多一个 ids */
@Data
@EqualsAndHashCode(callSuper = true)
public class CountQuery extends PageQuery {
    /** 单号、仓库、盘点人、物料编码 / 名称 / SKU 码 */
    private String keyword;
    private List<DocStatus> status;
    private Long warehouseId;
    /** 盘点用途（日常盘点、期初录入……），取值见枚举 */
    private CountPurpose purpose;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate countDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate countDateTo;
    private boolean mine;
    /**
     * 待我审核：不列自己建的（自己建的单不能自己审，SelfAudit，甲方 11-21 答 Q10）；状态由调用方另给，不看 mine（和需求订单等一样）。
     * 范围照旧按数据范围：本人范围的人本来就看不到别人建的单，所以单级审核的审核人要给"全部"范围的角色
     */
    private boolean auditForMe;
    private List<Long> ids;
}
