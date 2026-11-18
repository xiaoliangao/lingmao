package com.lingmao.scm.module.inventory.service;

import com.lingmao.scm.module.inventory.dto.StockLogExportPlan;
import com.lingmao.scm.module.inventory.dto.StockLogQuery;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.LongConsumer;

public interface StockLogExportService {
    StockLogExportPlan prepareSync(StockLogQuery query);
    long watermark();
    long write(StockLogExportPlan plan, OutputStream output, LongConsumer checkpoint) throws IOException;
}
