package com.lingmao.scm.module.purchase.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** 改预计到货、跟进备注（前端 FollowForm）：两格都发，没填的发 null */
@Data
public class FollowReq {
    private LocalDate expectDate;
    @Size(max = 255, message = "跟进备注最多 255 个字")
    private String followNote;
}
