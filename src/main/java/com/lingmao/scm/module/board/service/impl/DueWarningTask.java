package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.common.jobs.HousekeepingTask;
import com.lingmao.scm.module.board.mapper.DueWarningMapper;
import com.lingmao.scm.module.board.mapper.DueWarningMapper.ArrivalDigest;
import com.lingmao.scm.module.board.mapper.DueWarningMapper.ProductionDigest;
import com.lingmao.scm.module.system.dto.NotifyEvent;
import com.lingmao.scm.module.system.service.NotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * 货期预警（甲方 11-27 答 Q65，RFC 019 §4）：每天早上 8:30 以后，给每个有预警的人发一条钉钉，说他跟的生产单、他的采购单
 * 有几张逾期、临期。不逐张发：一天一条，点开到手机待办的"预警"看明细。
 * <p>
 * 挂在后台任务的定时清理上（每分钟一轮、整个部署只一个实例在跑）。同一天每人只发一次：发件箱按"人 + 日期"去重，
 * 重启了、两台机器都跑了也不会多发。8:30 前不发；应用 8:30 以后才起来的，起来就发当天的
 */
@Component
@RequiredArgsConstructor
public class DueWarningTask implements HousekeepingTask {

    static final LocalTime SEND_AFTER = LocalTime.of(8, 30);
    /** 生产单临期天数，和 production 的 ProductionRules.NEAR_DAYS 一样 */
    static final int NEAR_DAYS = 7;
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    private final DueWarningMapper mapper;
    private final NotifyService notifyService;
    private final Clock clock;

    /** 这台机器上次发到哪天；重启后丢了没关系（去重兜底），只是省得每分钟都查一遍 */
    private volatile LocalDate doneFor;

    @Override
    public String name() {
        return "due-warning-digest";
    }

    @Override
    public void runUntil(BooleanSupplier stopping) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (today.equals(doneFor) || now.toLocalTime().isBefore(SEND_AFTER) || !notifyService.enabled(NotifyEvent.DUE_WARNING)) {
            return;
        }
        for (Digest d : digests(today)) {
            if (stopping.getAsBoolean()) {
                return;
            }
            notifyService.send(NotifyEvent.DUE_WARNING, d.userId(), d.userId() + ":" + today, "货期预警 " + MD.format(today),
                    d.markdown(), "/mobile-todo?tab=alerts");
        }
        doneFor = today;
    }

    /** 一个人的一条 */
    record Digest(long userId, String markdown) {
    }

    /** 每个有预警的人一条，按人 id 排；都是 0 的不发 */
    List<Digest> digests(LocalDate today) {
        Map<Long, List<String>> lines = new TreeMap<>();
        for (ProductionDigest p : mapper.selectProduction(today, today.plusDays(NEAR_DAYS))) {
            List<String> parts = new ArrayList<>();
            if (p.late() > 0) parts.add("已逾期 **" + p.late() + "** 张");
            if (p.near() > 0) parts.add(NEAR_DAYS + " 天内到期 " + p.near() + " 张");
            if (p.nodeLate() > 0) parts.add("节点延期 " + p.nodeLate() + " 张");
            if (!parts.isEmpty()) {
                lines.computeIfAbsent(p.userId(), k -> new ArrayList<>()).add("你跟的生产单：" + String.join("，", parts));
            }
        }
        for (ArrivalDigest a : mapper.selectArrival(today)) {
            if (a.late() > 0) {
                lines.computeIfAbsent(a.userId(), k -> new ArrayList<>()).add("你的采购单：到料逾期 **" + a.late() + "** 行");
            }
        }
        List<Digest> out = new ArrayList<>();
        lines.forEach((user, ls) -> out.add(new Digest(user,
                "#### 货期预警 " + MD.format(today) + "\n\n" + String.join("\n\n", ls) + "\n\n点开看是哪几张。")));
        return out;
    }
}
