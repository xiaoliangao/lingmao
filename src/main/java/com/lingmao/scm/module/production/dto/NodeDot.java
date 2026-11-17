package com.lingmao.scm.module.production.dto;

/** 列表、看板、手机卡片上的节点进度条：每个节点一格；state 是 DONE / LATE / WARN / RUNNING / TODO */
public record NodeDot(String nodeName, String state) {
}
