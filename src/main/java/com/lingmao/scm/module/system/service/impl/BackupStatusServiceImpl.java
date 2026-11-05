package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.module.system.dto.BackupStatusVO;
import com.lingmao.scm.module.system.service.BackupStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

@Slf4j
@Service
public class BackupStatusServiceImpl implements BackupStatusService {

    /** 每天备份一次。超过 36 小时没有新的，说明至少漏了一次；多留的半天给服务器重启、备份慢一点这类情况 */
    static final Duration STALE_AFTER = Duration.ofHours(36);

    /** 每日备份所在的目录：备份目录下的 daily/。没配备份目录（开发环境）时为 null */
    private final Path dailyDir;
    private final Clock clock;

    /** 备份目录来自环境变量 BACKUP_DIR（部署包的 compose.yml 里是 /backups，只读挂载） */
    public BackupStatusServiceImpl(@Value("${lingmao.backup.dir:}") String backupDir, Clock clock) {
        this.dailyDir = StringUtils.hasText(backupDir) ? Paths.get(backupDir, "daily") : null;
        this.clock = clock;
    }

    @Override
    public BackupStatusVO status() {
        if (dailyDir == null) {
            return BackupStatusVO.notConfigured();
        }
        DumpFile latest = latestDump();
        if (latest == null) {
            return BackupStatusVO.noBackup();
        }
        boolean stale = Duration.between(latest.modified(), clock.instant()).compareTo(STALE_AFTER) > 0;
        return new BackupStatusVO(true, latest.name(),
                OffsetDateTime.ofInstant(latest.modified(), clock.getZone()), latest.size(), stale);
    }

    /**
     * daily/ 下修改时间最新的 .dump。备份脚本先写临时文件（不以 .dump 结尾），读一遍确认完整才改成正式的名字，
     * 所以写到一半的不会被当成最新备份。
     * 目录不存在或读不了，当作一份都没有：页面标红，总会有人去查
     */
    private DumpFile latestDump() {
        if (!Files.isDirectory(dailyDir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dailyDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".dump"))
                    .map(this::describe)
                    .filter(Objects::nonNull)
                    .max(Comparator.comparing(DumpFile::modified))
                    .orElse(null);
        } catch (IOException | UncheckedIOException e) {
            log.warn("读不了备份目录 {}", dailyDir, e);
            return null;
        }
    }

    /** 文件的修改时间和大小。不是普通文件，或者正好被过期清理删掉了，返回 null */
    private DumpFile describe(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) {
                return null;
            }
            return new DumpFile(file.getFileName().toString(), attrs.lastModifiedTime().toInstant(), attrs.size());
        } catch (IOException e) {
            return null;
        }
    }

    private record DumpFile(String name, Instant modified, long size) {
    }
}
