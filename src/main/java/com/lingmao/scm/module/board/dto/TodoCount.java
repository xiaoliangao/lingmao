package com.lingmao.scm.module.board.dto;

/** "其他提醒"的一个数（docs/api/06 §2.3，前端 TODO_COUNT 管叫法和地址）；是 0 的不给 */
public record TodoCount(String key, long count) {
}
