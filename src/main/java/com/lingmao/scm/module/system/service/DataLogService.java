package com.lingmao.scm.module.system.service;

import java.util.List;
import com.lingmao.scm.module.system.dto.DataLogView;

public interface DataLogService {
    List<DataLogView> list(String table, Long rowId, String rootTable, Long rootId,
                           Long transactionId, Long beforeId, int limit);
}
