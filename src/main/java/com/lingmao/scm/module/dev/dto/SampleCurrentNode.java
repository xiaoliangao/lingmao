package com.lingmao.scm.module.dev.dto;
import java.time.LocalDate;
public record SampleCurrentNode(Long id,String code,String name,LocalDate planDate,Long ownerId,String ownerName,String state) { }
