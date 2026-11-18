package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.ExportFileSlot;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.UUID;

/** Private export files are never mapped to public /files URLs; authorization remains in the job service. */
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
