package com.lingmao.scm.module.techpack.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** POST /api/tech-packs/{id}/bom-defaults 的请求体：一次问几行（从物料库一次勾几种料加进来时） */
@Data
public class BomDefaultsReq {
    @NotNull(message = "items 不能为空")
    @Size(max = 200, message = "一次最多问 200 行")
    @Valid
    private List<BomDefaultReq> items;
}
