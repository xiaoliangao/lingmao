package com.lingmao.scm.module.biz.dto;

import java.util.List;

public record TagObjectVO(int version, List<TagVO> tags) {
    public TagObjectVO { tags = List.copyOf(tags); }
}
