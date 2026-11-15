package com.lingmao.scm.module.style.entity;

import lombok.Getter;

/**
 * 款式上的资料包状态（数据库设计 §8.1）：由资料包生效、作废时回写（第 3 批，RFC 005 §4 的 updateTechPackStatus）。
 * 这一批一直是 NONE
 */
@Getter
public enum TechPackStatus {
    NONE("无"),
    DEV("研发中"),
    PROD("已生效");

    private final String label;

    TechPackStatus(String label) {
        this.label = label;
    }
}
