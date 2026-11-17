package com.lingmao.scm.module.techpack.entity;

import lombok.Getter;

/**
 * 资料包版本的状态（docs/api/01 §2）：草稿能改；生效后只读，要改点"修订"；同款同阶段新版本生效时，旧的自动变已作废（还能看、能导出）。
 * 没有"待审核"：有 techpack:pack:publish 的人点生效就生效（Q10）。转换表在 TechPackServiceImpl.FLOW
 */
@Getter
public enum PackStatus {
    DRAFT("草稿"),
    EFFECTIVE("已生效"),
    VOIDED("已作废");

    private final String label;

    PackStatus(String label) {
        this.label = label;
    }
}
