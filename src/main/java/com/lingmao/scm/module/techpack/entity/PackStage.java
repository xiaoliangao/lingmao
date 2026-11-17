package com.lingmao.scm.module.techpack.entity;

import lombok.Getter;

/**
 * 资料包的阶段（tp_tech_pack.stage，PRD §6.4.1）：研发 BOM 只估价；生产 BOM 发工厂、算料，需求订单只认它。
 * 版本号每个阶段各自从 V1 起。二期加 ORDER（生产单自己的资料包，架构 §4.7）
 */
@Getter
public enum PackStage {
    DEV("研发 BOM", "研发"),
    PROD("生产 BOM", "生产");

    private final String label;
    private final String shortLabel;

    PackStage(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }

    /** 版本的叫法：研发的带"研发"（"研发 V1"），生产的只写版本号（"V2"），和前端 packName() 一样 */
    public String versionName(int version) {
        return (this == DEV ? "研发 " : "") + "V" + version;
    }
}
