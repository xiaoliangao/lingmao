package com.lingmao.scm.module.inventory.service;

import com.lingmao.scm.module.inventory.dto.ExportJobView;
import com.lingmao.scm.module.inventory.dto.StockLogExportReq;
import java.nio.file.Path;

public interface ExportJobService {
    ExportJobView create(StockLogExportReq filters, String requestId);
    ExportJobView get(long id);
    ExportJobView cancel(long id);
    ExportJobView retry(long id);
    Download download(long id);
    record Download(Path path, String fileName) {}
}
