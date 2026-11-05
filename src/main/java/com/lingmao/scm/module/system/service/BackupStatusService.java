package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.BackupStatusVO;

/**
 * 备份状态：只读备份目录里最新一份的时间和大小，不做备份。
 * 备份本身由部署包里的 backup 容器每天执行（deploy/package/scripts/backup.sh），应用只读挂载备份目录
 */
public interface BackupStatusService {

    BackupStatusVO status();
}
