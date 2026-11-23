package com.lingmao.scm.module.dev.dto;
import java.time.LocalDate;
import java.util.List;
public record SampleDispatchPreview(List<Node> nodes) {
    public record Node(int seq,String nodeCode,String nodeName,int durationDays,LocalDate planDate) { }
}
