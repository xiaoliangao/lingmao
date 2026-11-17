package com.lingmao.scm.module.inventory.dto;

/** 新建盘点单的返回（前端 CountCreated = DocSaved + itemCount） */
public record CountCreatedVO(long id, String no, int version, int itemCount) {
}
