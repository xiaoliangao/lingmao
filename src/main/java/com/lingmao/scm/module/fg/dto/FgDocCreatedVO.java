package com.lingmao.scm.module.fg.dto;

/**
 * 新建、由回货单生成的返回（前端 FgDocCreated）
 *
 * @param status post = true 时已经审核过账了，是 APPROVED
 */
public record FgDocCreatedVO(long id, String no, int version, String status) {
}
