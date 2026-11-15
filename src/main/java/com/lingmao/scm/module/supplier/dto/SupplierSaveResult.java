package com.lingmao.scm.module.supplier.dto;

import com.lingmao.scm.module.supplier.entity.ChangeItem;

import java.util.List;

/**
 * 保存供应商的结果（RFC 004 §4 第 5 步）：关键字段里哪些直接生效了、哪些挂起等审批，前端分别提示
 * "已保存""账期 30 天 → 60 天 等审批"。before、after 是给人看的文字（同 ChangeVO）。普通字段改了什么不列。
 * 新建时 id 是新供应商的 id
 */
public record SupplierSaveResult(Long id, List<ChangeItem> applied, List<ChangeItem> pending) {
}
