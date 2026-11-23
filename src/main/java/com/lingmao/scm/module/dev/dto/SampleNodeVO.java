package com.lingmao.scm.module.dev.dto;
import java.time.LocalDate;
public record SampleNodeVO(Long id,int seq,String nodeCode,String nodeName,int durationDays,LocalDate planDate,
    LocalDate doneDate,Long doneBy,String doneByName,Long ownerId,String ownerName,String remark,String state) { }
