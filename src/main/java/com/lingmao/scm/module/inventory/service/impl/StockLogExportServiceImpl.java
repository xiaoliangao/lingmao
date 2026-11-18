package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.excel.StreamingExcel;
import com.lingmao.scm.common.jobs.JobFailure;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.inventory.dto.StockLogExportPlan;
import com.lingmao.scm.module.inventory.dto.StockLogExportReq;
import com.lingmao.scm.module.inventory.dto.StockLogQuery;
import com.lingmao.scm.module.inventory.dto.StockLogRowVO;
import com.lingmao.scm.module.inventory.mapper.StockLogMapper;
import com.lingmao.scm.module.inventory.service.StockLogExportService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.FieldPermissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockLogExportServiceImpl implements StockLogExportService {
    private final StockLogMapper mapper;
    private final StockExcel excel;
    private final MaterialService materials;
    private final UserService users;
    private final DictDataService dictionaries;

    @Override public long watermark() { return mapper.exportWatermark(); }

    @Override public StockLogExportPlan prepareSync(StockLogQuery query) {
        var filters = StockLogExportReq.from(query);
        var q = filters.query(); validate(q);
        long watermark = watermark();
        // A bounded seek determines eligibility before the response has headers or XLSX bytes.
        var probe = mapper.selectExportBatch(q, Keywords.codes(q.getKeyword()), Keywords.prefix(q.getKeyword()), watermark, null, null, 10_001);
        if (probe.size() > 10_000) throw new BizException("库存日志超过 10000 行，请创建后台导出任务");
        return new StockLogExportPlan(filters, watermark, FieldPermissions.has(FieldPermissions.COST), 10_000);
    }

    private static void validate(StockLogQuery q) {
        if (q.getIds() != null && q.getIds().size() > 100) throw new BizException(400, "一次最多选择 100 个流水编号");
        if (q.getKeyword() != null && q.getKeyword().length() > 120) throw new BizException(400, "筛选关键词过长");
    }

    @Override public long write(StockLogExportPlan plan, OutputStream output, LongConsumer checkpoint) throws IOException {
        var q = plan.filters().query(); validate(q);
        var units = InvTexts.labels(dictionaries, "unit");
        var codes = Keywords.codes(q.getKeyword()); var prefix = Keywords.prefix(q.getKeyword());
        return StreamingExcel.write(excel.logSchema(plan.financialColumns()), output, sink -> {
            OffsetDateTime afterTime = null; Long afterId = null;
            while (true) {
                checkpoint.accept(sink.rows());
                var rows = mapper.selectExportBatch(q, codes, prefix, plan.watermark(), afterTime, afterId, 500);
                if (rows.isEmpty()) break;
                if (sink.rows() + rows.size() > plan.maxRows()) throw new JobFailure(JobFailure.Code.ROW_LIMIT, true);
                var briefs = materials.skuBriefs(rows.stream().map(StockLogRowVO::getMaterialSkuId).toList());
                var names = users.nicknames(rows.stream().map(StockLogRowVO::getCreateBy).filter(Objects::nonNull).collect(Collectors.toSet()));
                rows.forEach(row -> { row.fillFrom(briefs.get(row.getMaterialSkuId())); row.setOperatorName(row.getCreateBy() == null ? null : names.get(row.getCreateBy())); });
                for (var row : excel.logRows(rows, units)) sink.row(row);
                sink.flush(); // IOException terminates here; no subsequent mapper call is made.
                checkpoint.accept(sink.rows());
                var last = rows.getLast(); afterTime = last.getCreateTime(); afterId = last.getId();
                if (rows.size() < 500) break;
            }
        });
    }
}
