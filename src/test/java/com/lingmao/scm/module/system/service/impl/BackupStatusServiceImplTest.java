package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.module.system.dto.BackupStatusVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BackupStatusServiceImplTest {

    /** 固定的"现在"：2026-11-21 10:00（东八区）。用固定时钟，"36 小时前"才是确定的 */
    private final Clock clock = Clock.fixed(OffsetDateTime.parse("2026-11-21T10:00:00+08:00").toInstant(),
            ZoneId.of("Asia/Shanghai"));

    /** 当作备份目录；每日备份在它下面的 daily/ */
    @TempDir
    Path backupDir;

    /** 规则：没配备份目录（开发环境）→ configured 为 false，其余为空，也不标红 */
    @Test
    void status_notConfigured() {
        BackupStatusVO status = new BackupStatusServiceImpl("", clock).status();

        assertThat(status.configured()).isFalse();
        assertThat(status.latestFile()).isNull();
        assertThat(status.latestTime()).isNull();
        assertThat(status.sizeBytes()).isNull();
        assertThat(status.stale()).isFalse();
    }

    /** 规则：配了，但一份备份都没有 → 标红 */
    @Test
    void status_emptyDir_isStale() throws IOException {
        Files.createDirectories(backupDir.resolve("daily"));

        BackupStatusVO status = service().status();

        assertThat(status.configured()).isTrue();
        assertThat(status.latestFile()).isNull();
        assertThat(status.stale()).isTrue();
    }

    /** 规则：取修改时间最新的 .dump，正在写的临时文件不算；它在 36 小时以内 → 不标红 */
    @Test
    void status_latestWithin36Hours_notStale() throws IOException {
        dump("scm-20261119-023000.dump", 10, "2026-11-19T02:30:00+08:00");
        dump("scm-20261120-023000.dump", 2048, "2026-11-20T02:30:00+08:00");         // 31.5 小时前
        dump(".scm-20261121-093000.dump.tmp", 5, "2026-11-21T09:55:00+08:00");        // 备份脚本正在写的

        BackupStatusVO status = service().status();

        assertThat(status.configured()).isTrue();
        assertThat(status.latestFile()).isEqualTo("scm-20261120-023000.dump");
        assertThat(status.latestTime()).isEqualTo(OffsetDateTime.parse("2026-11-20T02:30:00+08:00"));
        assertThat(status.sizeBytes()).isEqualTo(2048L);
        assertThat(status.stale()).isFalse();
    }

    /** 规则：最新的一份也超过 36 小时了 → 标红 */
    @Test
    void status_latestOlderThan36Hours_isStale() throws IOException {
        dump("scm-20261119-023000.dump", 10, "2026-11-19T02:30:00+08:00");
        dump("scm-20261119-213000.dump", 20, "2026-11-19T21:30:00+08:00");          // 36.5 小时前

        BackupStatusVO status = service().status();

        assertThat(status.latestFile()).isEqualTo("scm-20261119-213000.dump");
        assertThat(status.stale()).isTrue();
    }

    private BackupStatusServiceImpl service() {
        return new BackupStatusServiceImpl(backupDir.toString(), clock);
    }

    /** 在 daily/ 下造一个指定大小、指定修改时间的文件 */
    private void dump(String name, int size, String modified) throws IOException {
        Path file = backupDir.resolve("daily").resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[size]);
        Files.setLastModifiedTime(file, FileTime.from(OffsetDateTime.parse(modified).toInstant()));
    }
}
