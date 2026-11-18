package com.lingmao.scm.module.production.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 改节点计划：全部节点一起发（docs/api/05 §3.3.2） */
@Data
public class NodePlanReq {
    @NotNull(message = "缺少 planRevision")
    @PositiveOrZero(message = "planRevision 不能小于0")
    private Integer planRevision;
    @NotEmpty(message = "节点不能为空")
    @Valid
    private List<Item> nodes;

    @Data
    public static class Item {
        @NotNull(message = "缺少 nodeId")
        private Long nodeId;
        private LocalDate planStart;
        private LocalDate planFinish;
    }
}
