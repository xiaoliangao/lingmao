package com.lingmao.scm.module.system.dto;

import java.util.Set;

/**
 * 按规则拼好、只差流水的编码（RFC 018 §4.5）：prefix + 流水（width 位，不够补 0）+ suffix。
 * key 是 sys_serial 的流水键：流水在最后时就是 prefix（和改成规则以前的 key 一样，接着往下编），不在最后时 prefix#suffix。
 *
 * @param ruleName 用的是哪条规则（报错时说）
 * @param used     规则里有哪几种段：款号看它决定要不要锁设计师编号
 */
public record CodeTemplate(String prefix, String suffix, int width, String ruleName, Set<CodeSegment.Type> used) {

    public String key() {
        return suffix.isEmpty() ? prefix : prefix + "#" + suffix;
    }

    /** 流水最大到几：2 位是 99 */
    public int maxSeq() {
        return (int) Math.pow(10, width) - 1;
    }

    public String format(int seq) {
        return prefix + String.format("%0" + width + "d", seq) + suffix;
    }
}
