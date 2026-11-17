package com.lingmao.scm.module.inventory.dto;

/**
 * 新建、由源单生成的返回（前端 InvDocCreated = DocSaved + status）
 *
 * @param status post = true 时已经审核过账了，是 APPROVED
 */
public record InvDocCreatedVO(long id, String no, int version, String status) {
}
