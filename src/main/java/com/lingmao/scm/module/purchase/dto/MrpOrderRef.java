package com.lingmao.scm.module.purchase.dto;

import java.util.List;

/** 算料单里的一张订单（前端 MrpOrderRef） */
public record MrpOrderRef(long orderId, String orderNo, List<String> styleCodes) {
}
