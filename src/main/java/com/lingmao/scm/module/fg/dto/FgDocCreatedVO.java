package com.lingmao.scm.module.fg.dto;

/**
 * 新建、由回货单生成的返回（前端 FgDocCreated）
 *
 * @param status 新建为 DRAFT，后续由另一审核人通过领域动作过账
 */
public record FgDocCreatedVO(long id, String no, int version, String status) {
}
