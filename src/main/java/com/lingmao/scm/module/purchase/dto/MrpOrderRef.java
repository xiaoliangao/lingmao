package com.lingmao.scm.module.purchase.dto;

import java.util.List;

/**
 * 算料单里的一张订单（前端 MrpOrderRef）。canOpen：当前用户打得开这张订单的详情（有订单查看权限、在他的数据范围和品类线里）——
 * "本人"范围的采购算的是别人的订单（PRD §3.2 算料例外），打不开，页面上只显示单号不给链接
 */
public record MrpOrderRef(long orderId, String orderNo, List<String> styleCodes, boolean canOpen) {
}
