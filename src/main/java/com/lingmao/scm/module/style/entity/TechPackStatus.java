package com.lingmao.scm.module.style.entity;

import lombok.Getter;

/**
 * 款式上的资料包状态（数据库设计 §8.1）：资料包生效时回写（RFC 005 §4 的 updateTechPackStatus）。
 * 原来的 DEV（研发中）11-18 去掉了：研发不填物料清单（PRD Q24），V38 确认库里没有
 */
@Getter
public enum TechPackStatus {
    NONE("无"),
    PROD("已生效");

    private final String label;

    TechPackStatus(String label) {
        this.label = label;
    }
}
