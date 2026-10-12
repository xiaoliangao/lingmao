package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 只装一组 id 的入参：给角色分配菜单、给用户分配角色都用它。传空数组表示全部取消 */
@Data
public class IdsReq {

    @NotNull(message = "不能为空，全部取消请传 []")
    private List<Long> ids;
}
