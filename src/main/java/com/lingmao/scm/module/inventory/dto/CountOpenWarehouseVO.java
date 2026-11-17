package com.lingmao.scm.module.inventory.dto;

/** 有没审核的盘点单（草稿、待审核）的仓库（前端 CountOpenWarehouse） */
public record CountOpenWarehouseVO(Long warehouseId, Long countId, String countNo, String status) {
}
