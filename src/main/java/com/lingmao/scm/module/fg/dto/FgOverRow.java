package com.lingmao.scm.module.fg.dto;

/** 1004 超出上限的一行（docs/api/07 §3.5.2）：回货入库超过还没入的良品、发货超过最多还能发 */
public record FgOverRow(int seq, String skuCode, String sourceNo, int thisQty, long maxQty, String reason) {
}
