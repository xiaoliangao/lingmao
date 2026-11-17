package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;

import java.util.Set;

/**
 * 给需求订单用的一版资料包（docs/api/02 §3.4、§3.6、§3.14）：录单时哪些颜色能下、审核时锁哪一版、变更单换版本、加颜色时查配没配料。
 *
 * @param colorIds 按款色配料的行（随款色、随款色和尺码）每一行都配了的款色；null = 这一版没有按款色配料的行，哪个颜色都能下
 */
public record PackBrief(long id, long styleId, PackStage stage, int version, PackStatus status, Set<Long> colorIds) {

    /** 这个款色在这一版里配了料 */
    public boolean covers(long colorId) {
        return colorIds == null || colorIds.contains(colorId);
    }

    /** "V2"（研发的"研发 V2"） */
    public String versionName() {
        return stage.versionName(version);
    }
}
