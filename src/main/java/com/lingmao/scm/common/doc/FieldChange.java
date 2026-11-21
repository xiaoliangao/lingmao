package com.lingmao.scm.common.doc;

/**
 * 操作记录里一个字段的"改前 → 改后"（RFC 016 §3）：存显示用的文字，字典存中文、人和供应商存名字、数字去掉多余的 0，
 * 字典以后改名，历史还是当时的叫法。空就是 null。
 * <p>
 * permission 不是空时，没有这个权限的人看记录只知道"改了"，看不到前后的值（如物料参考单价要"看成本"）
 *
 * @param field      字段名，和接口里的一样（如 refPrice）
 * @param label      页面上的叫法（如"参考单价"）
 * @param before     改前
 * @param after      改后
 * @param permission 看值要的权限码，不用权限是 null
 */
public record FieldChange(String field, String label, String before, String after, String permission) {
}
