package com.lingmao.scm.module.board.dto;

import java.util.List;

/** 绩效表：各行和合计行（合计行的 id 是 null） */
public record PerfResult<T>(List<T> rows, T total) {
}
