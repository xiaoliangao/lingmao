package com.lingmao.scm.module.dev.dto;
import java.util.List;
public record SampleVersionPage(List<SampleOrderVO> list,boolean hasMore,Integer nextBeforeVersion) { }
