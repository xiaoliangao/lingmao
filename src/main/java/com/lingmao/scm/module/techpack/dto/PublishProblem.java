package com.lingmao.scm.module.techpack.dto;

/** 生效检查没通过的一条（1000 的 data，一次列全）：row 是物料清单第几行（从 1 起），0 说的是整个资料包 */
public record PublishProblem(int row, String message) {
}
