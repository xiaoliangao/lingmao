package com.lingmao.scm.module.supplier.dto;

import com.lingmao.scm.module.supplier.entity.SupplierStatus;

/** 供应商下拉的一项（SupplierSelect）：登录就能取，只带这几列 */
public record SupplierOption(Long id, String code, String shortName, SupplierStatus status) {
}
