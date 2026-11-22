package com.lingmao.scm.module.production.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.production.dto.BoardCards;
import com.lingmao.scm.module.production.dto.BoardFactoryRow;
import com.lingmao.scm.module.production.dto.BoardQuery;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.production.service.ProductionBoardService;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.security.DataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;

/** 生产看板（docs/api/05 §3.7）：后端在 production（架构 §3.1）。列表的行和外发加工单列表同一段 SQL */
@Service
@RequiredArgsConstructor
public class ProductionBoardServiceImpl implements ProductionBoardService {

    private final ProductionOrderMapper prdMapper;
    private final ProductionSupport support;
    private final SupplierService supplierService;
    private final DictDataService dictDataService;

    /** 看板的筛选换成列表的：只看已下单、生产中 */
    private static ProductionQuery toQuery(BoardQuery b) {
        ProductionQuery q = new ProductionQuery();
        q.setKind(b.getKind());
        q.setFactoryId(b.getFactoryId());
        q.setFactoryIds(b.getFactoryIds());
        q.setMerchId(b.getMerchId());
        q.setBrand(b.getBrand());
        q.setDueDateFrom(b.getDueDateFrom());
        q.setDueDateTo(b.getDueDateTo());
        q.setMine(b.isMine());
        q.setCard(b.getCard());
        q.setStatus(List.of(APPROVED, RUNNING));
        return q;
    }

    @Override
    public BoardCards cards(BoardQuery query) {
        ProductionQuery q = toQuery(query);
        DataScope scope = DataScope.current(q.isMine());
        ProductionOrderMapper.CardRow c = prdMapper.selectCards(q, scope, support.today(), support.nearUntil());
        LocalDate today = support.today();
        long returned = prdMapper.selectReturnedQty(q, scope, today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()));
        return new BoardCards(c.runningCount(), c.runningQty(), c.waitingCount(), c.waitingQty(), c.nearCount(), c.lateCount(),
                c.nodeLateCount(), c.issueCount(), returned, ProductionRules.NEAR_DAYS);
    }

    @Override
    public PageResult<ProductionRowVO> orders(BoardQuery query) {
        IPage<ProductionRowVO> page = prdMapper.selectRowPage(query.toPage(), toQuery(query), null, DataScope.current(query.isMine()),
                support.today(), support.nearUntil());
        support.fillRows(page.getRecords(), true);
        return PageResult.of(page);
    }

    @Override
    public List<BoardFactoryRow> factories(BoardQuery query) {
        if (query.getFactoryIds() != null && query.getFactoryIds().size() > 200) {
            throw new com.lingmao.scm.common.exception.BizException(400, "一次最多查询 200 个加工厂");
        }
        ProductionQuery q = toQuery(query);
        q.setFactoryId(null);
        q.setCard(null);
        List<ProductionOrderMapper.FactoryLoadRow> rows = prdMapper.selectFactoryLoads(q, DataScope.current(q.isMine()), support.today(),
                support.nearUntil(), SupplierService.FACTORY_CATEGORIES);
        Map<Long, List<String>> categories = supplierService.categories(rows.stream().map(ProductionOrderMapper.FactoryLoadRow::factoryId)
                .toList());
        Map<String, String> names = dictDataService.listByType("supplier_category").stream()
                .collect(Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
        List<BoardFactoryRow> out = new ArrayList<>();
        for (ProductionOrderMapper.FactoryLoadRow r : rows) {
            BoardFactoryRow v = new BoardFactoryRow();
            v.setFactoryId(r.factoryId());
            v.setFactoryShortName(r.factoryShortName());
            v.setCategoryNames(categories.getOrDefault(r.factoryId(), List.of()).stream()
                    .filter(SupplierService.FACTORY_CATEGORIES::contains).map(c -> names.getOrDefault(c, c)).toList());
            v.setWipCount(r.wipCount());
            v.setWipQty(r.wipQty());
            v.setLateCount(r.lateCount());
            out.add(v);
        }
        return out;
    }
}
