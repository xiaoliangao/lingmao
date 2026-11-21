package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 入库单、出库单列表（docs/api/04 §3.2.1）。导出也用它，多一个 ids（只导选中的） */
@Data
@EqualsAndHashCode(callSuper = true)
public class InvDocQuery extends PageQuery {

    /** IN 入库单 / OUT 出库单，必填 */
    @NotNull(message = "kind 必填：IN 入库单 / OUT 出库单")
    private InvDocKind kind;
    /** 单据类型，取值见枚举；不传是这一边（入库或出库）的全部 */
    private InvDocType docType;
    private List<DocStatus> status;
    /** 出仓或入仓是它 */
    private Long warehouseId;
    private Long partnerId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate docDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate docDateTo;
    /** 单号、源单号、送货单号、物流单号、物料编码 / 名称 / SKU 码、缸号；有空格 = 按几个单号精确查 */
    private String keyword;
    private boolean mine;
    /**
     * 待我审核：不列自己建的（自己建的单不能自己审，SelfAudit，甲方 11-21 答 Q10）；状态由调用方另给，不看 mine（和需求订单等一样）。
     * 范围照旧按数据范围：本人范围的人本来就看不到别人建的单，所以单级审核的审核人要给"全部"范围的角色
     */
    private boolean auditForMe;
    private List<Long> ids;
}
