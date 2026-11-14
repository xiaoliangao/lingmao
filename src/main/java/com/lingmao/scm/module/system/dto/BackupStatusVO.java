package com.lingmao.scm.module.system.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;

/**
 * 最近一次每日备份，显示在首页（只有超级管理员看得到）。
 * configured：服务器上配了备份目录没有（开发环境没配）。没配时其余字段为 null，stale 为 false，页面不显示这一条。
 * latestFile、latestTime、sizeBytes：备份目录 daily/ 下最新的那份；一份都没有时为 null。
 * stale：配了，但超过 36 小时没有新备份（一份都没有也算），页面标红：备份停了要有人知道（RFC 002 §8）
 */
public record BackupStatusVO(boolean configured,
                             String latestFile,
                             @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
                             OffsetDateTime latestTime,
                             Long sizeBytes,
                             boolean stale) {

    public static BackupStatusVO notConfigured() {
        return new BackupStatusVO(false, null, null, null, false);
    }

    public static BackupStatusVO noBackup() {
        return new BackupStatusVO(true, null, null, null, true);
    }
}
