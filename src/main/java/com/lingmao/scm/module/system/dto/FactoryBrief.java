package com.lingmao.scm.module.system.dto;

/**
 * 工厂账号对应的那家（RFC 019 §7）：用户管理、/api/auth/me 显示用
 *
 * @param factory 是加工厂或成衣厂（供应商分类有 GARMENT、PROCESSOR）：不是的不能开工厂账号
 * @param active  合作中：停用、黑名单、还没准入的不能新开工厂账号
 */
public record FactoryBrief(Long id, String code, String shortName, boolean factory, boolean active) {
}
