package com.lingmao.scm.module.techpack.entity;

import lombok.Getter;

/**
 * 资料包的阶段（tp_tech_pack.stage，PRD §6.4.1）。一期只有生产 BOM：发工厂、算料，需求订单锁它。
 * 研发不填物料清单（甲方 11-18 答 Q24），原来的 DEV 去掉了（V38 确认库里没有）。二期加 ORDER（生产单自己的资料包，架构 §4.7）
 */
@Getter
public enum PackStage {
    PROD("生产 BOM");

    private final String label;

    PackStage(String label) {
        this.label = label;
    }

    /** 版本的叫法："V2"，和前端 packName() 一样 */
    public String versionName(int version) {
        return "V" + version;
    }
}
