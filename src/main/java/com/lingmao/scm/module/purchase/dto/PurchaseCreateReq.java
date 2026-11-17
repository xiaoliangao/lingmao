package com.lingmao.scm.module.purchase.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 新建采购单（POST /api/purchase-orders） */
@Data
@EqualsAndHashCode(callSuper = true)
public class PurchaseCreateReq extends PurchaseForm {
}
