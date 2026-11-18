package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.ExportFileSlot;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.UUID;

/** 私有导出文件从不映射到公开的 /files 地址；权限由导出任务服务把关。 */
public interface ExportFileStore {
    long MAX_BYTES = 250L * 1024 * 1024;
    ExportFileSlot allocate(long jobId, String leaseToken) throws IOException;
    OutputStream output(ExportFileSlot slot) throws IOException;
    void checkCapacity() throws IOException;
    long publish(ExportFileSlot slot) throws IOException;
    void discard(ExportFileSlot slot);
    Path path(long jobId, UUID token);
    void delete(long jobId, UUID token);
}
