package com.lingmao.scm.module.purchase.dto;

/** 待到料页签上的数（前端 PendingArrivalCounts） */
public record PendingArrivalCounts(long all, long late, long near, long cut) {
}
