package com.lingmao.scm.module.inventory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelExport;
import com.lingmao.scm.common.excel.ExcelImport;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.entity.Company;
import com.lingmao.scm.module.basic.entity.MaterialCategory;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.basic.service.MaterialCategoryService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocActions;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.inventory.dto.CountAddItemsReq;
import com.lingmao.scm.module.inventory.dto.CountCategoryVO;
import com.lingmao.scm.module.inventory.dto.CountCreateReq;
import com.lingmao.scm.module.inventory.dto.CountCreatedVO;
import com.lingmao.scm.module.inventory.dto.CountDetailVO;
import com.lingmao.scm.module.inventory.dto.CountItemVO;
import com.lingmao.scm.module.inventory.dto.CountOpenWarehouseVO;
import com.lingmao.scm.module.inventory.dto.CountQuery;
import com.lingmao.scm.module.inventory.dto.CountRowVO;
import com.lingmao.scm.module.inventory.dto.CountSaveReq;
import com.lingmao.scm.module.inventory.entity.CountPurpose;
import com.lingmao.scm.module.inventory.entity.CountScope;
import com.lingmao.scm.module.inventory.entity.InvCount;
import com.lingmao.scm.module.inventory.entity.InvCountCategory;
import com.lingmao.scm.module.inventory.entity.InvCountItem;
import com.lingmao.scm.module.inventory.entity.Stock;
import com.lingmao.scm.module.inventory.entity.StockChangeType;
import com.lingmao.scm.module.inventory.entity.StockLog;
import com.lingmao.scm.module.inventory.mapper.InvCountCategoryMapper;
import com.lingmao.scm.module.inventory.mapper.InvCountItemMapper;
import com.lingmao.scm.module.inventory.mapper.InvCountMapper;
import com.lingmao.scm.module.inventory.mapper.StockLogMapper;
import com.lingmao.scm.module.inventory.mapper.StockMapper;
import com.lingmao.scm.module.inventory.service.CountService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.DRAFT;
import static com.lingmao.scm.common.doc.DocStatus.PENDING;
import static com.lingmao.scm.common.doc.DocStatus.VOIDED;

/**
 * 物料盘点单（docs/api/04 §3.5、PRD §6.6.8）。建单时按范围带出明细、拍下账面数；审核时差异（实盘 − 账面）加到现在的库存上，
 * 建单以后的进出不受影响（规则 2）。一个仓同时只能有一张没审核的（规则 4，uk_inv_count_open 兜底）。
 * 期初录入：账面数都是 0、只能手选、已经有库存的 SKU 不能进，审核时日志记"期初"。
 * 过过账（有库存日志）的单再回到草稿后不能删、只能作废，和入库单、出库单一样
 */
@Service
@RequiredArgsConstructor
public class CountServiceImpl implements CountService {
    private final com.lingmao.scm.module.biz.service.TagFilterService tagFilters;

    /** 状态转换表（docs/api/04 §2.2）。remove、void 都从草稿来，过没过过账决定给哪个 */
    static final StatusFlow<DocStatus> FLOW = StatusFlow.of(DocStatus.class)
            .on("edit", DRAFT).to(DRAFT)
            .on("refreshBook", DRAFT).to(DRAFT)
            .on("submit", DRAFT).to(PENDING)
            .on("withdraw", PENDING).to(DRAFT)
            .on("reject", PENDING).to(DRAFT)
            .on("audit", PENDING).to(APPROVED)
            .on("unaudit", APPROVED).to(DRAFT)
            .on("remove", DRAFT).to(DRAFT)
            .on("void", DRAFT).to(VOIDED)
            .build();

    static final String PERM = "inventory:count:";

    private final InvCountMapper countMapper;
    private final InvCountItemMapper itemMapper;
    private final InvCountCategoryMapper categoryMapper;
    private final StockMapper stockMapper;
    private final StockLogMapper stockLogMapper;
    private final StockLedger ledger;
    private final CountExcel excel;
    private final MaterialService materialService;
    private final MaterialCategoryService materialCategoryService;
    private final WarehouseService warehouseService;
    private final CompanyService companyService;
    private final UserService userService;
    private final DocHeaderService docHeaderService;
    private final DocLogService docLogService;
    private final AttachmentService attachmentService;
    private final SerialNoGenerator serialNoGenerator;
    private final Clock clock;
    private final com.lingmao.scm.module.system.service.FileImportService imports;

    // ---------- 查询 ----------

    @Override
    public PageResult<CountRowVO> page(CountQuery query) {
        tagFilters.prepare(query);
        IPage<CountRowVO> page = countMapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()), DataScope.current(query.isMine()));
        fillRows(page.getRecords());
        return PageResult.of(page);
    }

    private void fillRows(List<CountRowVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, String> names = userService.nicknames(rows.stream().map(CountRowVO::getCounterId).collect(Collectors.toSet()));
        List<Long> drafts = rows.stream().filter(r -> DRAFT.name().equals(r.getStatus())).map(CountRowVO::getId).toList();
        Set<Long> posted = drafts.isEmpty() ? Set.of() : stockLogMapper.postedCounts(drafts);
        for (CountRowVO r : rows) {
            r.setCounterName(names.get(r.getCounterId()));
            r.setScopeText(scopeText(r.getScope(), r.getCategoryNames(), r.getItemCount()));
            r.setActions(actions(DocStatus.valueOf(r.getStatus()), posted.contains(r.getId()), r.getCreateBy()));
        }
    }

    static String scopeText(CountScope scope, String categoryNames, int itemCount) {
        return switch (scope) {
            case ALL -> "全部物料";
            case CATEGORY -> "分类：" + (categoryNames == null ? "" : categoryNames);
            case PICKED -> "手选 " + itemCount + " 种";
        };
    }

    /** 按钮：撤回只给制单人；没过过账的草稿给"删除"，过过账的给"作废" */
    static List<String> actions(DocStatus status, boolean posted, Long createBy) {
        return DocActions.of(FLOW, status, PERM)
                .perm("edit", PERM + "edit")
                .perm("refreshBook", PERM + "edit")
                .perm("remove", PERM + "remove")
                .perm("void", PERM + "remove")
                .when("withdraw", Objects.equals(createBy, LoginUser.currentUserId()))
                .when("remove", !posted)
                .when("void", posted)
                .list();
    }

    @Override
    public CountDetailVO detail(long id) {
        return detail(getVisible(id));
    }

    private CountDetailVO detail(InvCount count) {
        long id = count.getId();
        List<InvCountItem> items = itemMapper.selectByCount(id);
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(items.stream().map(InvCountItem::getMaterialSkuId).toList());
        Map<Long, BigDecimal> adjusted = count.getStatus() == APPROVED
                ? stockLogMapper.sumByCountItem(id).stream().collect(Collectors.toMap(StockLog::getCountItemId, StockLog::getChangeQty))
                : Map.of();
        WarehouseVO warehouse = warehouseService.getById(count.getWarehouseId());

        CountDetailVO vo = docHeaderService.fill(new CountDetailVO(), BizType.INV_COUNT, count, count.getStatus());
        vo.setCompanyName(companyService.getById(count.getCompanyId()).getShortName());
        vo.setWarehouseId(warehouse.getId());
        vo.setWarehouseName(warehouse.getName());
        vo.setWarehouseType(warehouse.getType());
        vo.setPurpose(count.getPurpose());
        vo.setScope(count.getScope());
        List<CountCategoryVO> categories = categories(id);
        vo.setCategories(categories);
        vo.setScopeText(scopeText(count.getScope(), categories.stream()
                .map(c -> (c.parentName() == null ? "" : c.parentName() + "-") + c.name()).collect(Collectors.joining("、")), items.size()));
        vo.setCountDate(count.getCountDate());
        vo.setCounterId(count.getCounterId());
        vo.setCounterName(userService.nicknames(List.of(count.getCounterId())).get(count.getCounterId()));
        vo.setBookTime(count.getBookTime());
        Set<Long> postedItems = stockLogMapper.postedCountItems(id);
        vo.setItems(items.stream().map(i -> {
            CountItemVO v = new CountItemVO();
            v.fillFrom(briefs.get(i.getMaterialSkuId()));
            v.setId(i.getId());
            v.setSeq(i.getSeq());
            v.setBookQty(i.getBookQty());
            v.setBookRevision(i.getBookRevision());
            v.setCountedQty(i.getCountedQty());
            v.setDiffQty(i.getCountedQty() == null ? null : i.getCountedQty().subtract(i.getBookQty()));
            v.setAdjustedQty(count.getStatus() == APPROVED ? adjusted.getOrDefault(i.getId(), BigDecimal.ZERO) : null);
            v.setRemark(i.getRemark());
            v.setPosted(postedItems.contains(i.getId()));
            return v;
        }).toList());
        vo.setItemCount(items.size());
        vo.setFilledCount((int) items.stream().filter(i -> i.getCountedQty() != null).count());
        vo.setGainCount((int) items.stream().filter(i -> diff(i) > 0).count());
        vo.setLossCount((int) items.stream().filter(i -> diff(i) < 0).count());
        vo.setMovesSinceBook(count.getStatus() == PENDING && !items.isEmpty()
                ? stockLogMapper.countMovesSince(count.getWarehouseId(), items.stream().map(InvCountItem::getMaterialSkuId).toList(),
                count.getBookTime(), id)
                : 0);
        boolean posted = !postedItems.isEmpty();
        vo.setPosted(posted);
        vo.setActions(actions(count.getStatus(), posted, count.getCreateBy()));
        return vo;
    }

    /** 差异的方向：没填实盘数的算 0 */
    private static int diff(InvCountItem i) {
        return i.getCountedQty() == null ? 0 : i.getCountedQty().compareTo(i.getBookQty());
    }

    private List<CountCategoryVO> categories(long countId) {
        List<CountCategoryVO> list = new ArrayList<>();
        for (Long categoryId : categoryMapper.categoryIds(countId)) {
            MaterialCategory c = materialCategoryService.getById(categoryId);
            String parent = c.getParentId() == null || c.getParentId() == 0 ? null
                    : materialCategoryService.getById(c.getParentId()).getName();
            list.add(new CountCategoryVO(c.getId(), c.getName(), parent));
        }
        return list;
    }

    @Override
    public List<CountOpenWarehouseVO> openWarehouses() {
        return countMapper.selectOpen();
    }

    @Override
    public String no(long id) {
        return getVisible(id).getNo();
    }

    @Override
    public void checkVisible(long id) {
        getVisible(id);
    }

    /** 数据范围：制单人或盘点人是自己（docs/api/04 §3.1） */
    private InvCount getVisible(long id) {
        InvCount count = countMapper.selectById(id);
        if (count == null) {
            throw new BizException(404, "单据不存在");
        }
        DataScope.current(false).check(count.getCreateBy(), count.getCounterId());
        com.lingmao.scm.common.scope.CompanyContext.target(count.getCompanyId());
        return count;
    }

    private InvCount lock(long id, int version) {
        InvCount count = countMapper.lockById(id);
        if (count == null) {
            throw new BizException(404, "单据不存在");
        }
        DataScope.current(false).check(count.getCreateBy(), count.getCounterId());
        com.lingmao.scm.common.scope.CompanyContext.target(count.getCompanyId());
        if (count.getVersion() != version) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张单已经被别人改过，请刷新后再改");
        }
        return count;
    }

    // ---------- 建单、存草稿、加物料、重新取账面数 ----------

    @Override
    @Transactional
    public CountCreatedVO create(CountCreateReq req) {
        com.lingmao.scm.common.scope.CompanyContext.create(req.getCompanyId());
        CountPurpose purpose = req.getPurpose();
        CountScope scope = req.getScope();
        if (purpose == CountPurpose.OPENING && scope != CountScope.PICKED) {
            throw new BizException(400, "期初录入只能手选物料");
        }
        List<Long> categoryIds = req.getCategoryIds() == null ? List.of() : req.getCategoryIds().stream().distinct().toList();
        List<Long> skuIds = req.getSkuIds() == null ? List.of() : req.getSkuIds().stream().filter(Objects::nonNull).distinct().toList();
        if (scope == CountScope.CATEGORY && categoryIds.isEmpty()) {
            throw new BizException(400, "请选物料分类");
        }
        if (purpose == CountPurpose.DAILY && scope == CountScope.PICKED && skuIds.isEmpty()) {
            throw new BizException(400, "请选要盘的物料");
        }
        Company company = companyService.getById(req.getCompanyId());
        if (!Integer.valueOf(1).equals(company.getStatus())) {
            throw new BizException("主体" + company.getShortName() + "已停用，请换一个");
        }
        WarehouseVO warehouse = warehouseService.getById(req.getWarehouseId());
        if ("PRODUCT".equals(warehouse.getType())) {
            throw new BizException(400, "成品仓不在这里盘（成品二期做）");
        }
        if (!Integer.valueOf(1).equals(warehouse.getStatus())) {
            throw new BizException("仓库" + warehouse.getName() + "已停用");
        }
        checkCounter(req.getCounterId());
        checkNoOpenCount(warehouse.getId(), null);
        materialCategoryService.requireExists(categoryIds);

        InvCount count = new InvCount();
        count.setNo(serialNoGenerator.daily(BizType.INV_COUNT.prefix(null)));
        count.setCompanyId(req.getCompanyId());
        count.setWarehouseId(warehouse.getId());
        count.setPurpose(purpose);
        count.setScope(scope);
        count.setCountDate(req.getCountDate());
        count.setCounterId(req.getCounterId());
        count.setBookTime(OffsetDateTime.now(clock));
        count.setStatus(DRAFT);
        count.setRemark(InvTexts.blankToNull(req.getRemark()));
        countMapper.insert(count);
        if (scope == CountScope.CATEGORY) {
            categoryMapper.insert(categoryIds.stream().map(c -> new InvCountCategory(count.getId(), c)).toList());
        }

        Map<Long, Book> books = scope == CountScope.PICKED ? pickedBooks(count, skuIds) : scopeBooks(count, categoryIds);
        List<InvCountItem> items = new ArrayList<>();
        books.forEach((skuId, book) -> items.add(newItem(count.getId(), items.size() + 1, skuId, book)));
        if (!items.isEmpty()) {
            itemMapper.insert(items);
        }
        docLogService.add(BizType.INV_COUNT, count.getId(), "create", items.size() + " 种物料");
        return new CountCreatedVO(count.getId(), count.getNo(), 0, items.size());
    }

    private void checkCounter(long counterId) {
        if (!userService.isEnabled(counterId)) {
            throw new BizException(400, "盘点人不存在或已停用");
        }
    }

    /** 一个仓同时只能有一张没审核的盘点单（PRD §6.6.8 规则 4）。exceptId：反审核时自己不算 */
    private void checkNoOpenCount(long warehouseId, Long exceptId) {
        for (CountOpenWarehouseVO open : countMapper.selectOpen()) {
            if (open.warehouseId() == warehouseId && !Objects.equals(open.countId(), exceptId)) {
                throw new BizException("这个仓库有一张没审核的盘点单 " + open.countNo() + "，先把它审核或删掉");
            }
        }
    }

    /** "全部" / "按分类"：这个仓有库存记录的 SKU（数量是 0 的也带），账面数 = 现在的库存，按 SKU 码排 */
    private Map<Long, Book> scopeBooks(InvCount count, List<Long> categoryIds) {
        return books(count, scopeSkus(count, categoryIds));
    }

    private List<Long> scopeSkus(InvCount count, List<Long> categoryIds) {
        return stockMapper.selectForCount(count.getWarehouseId(), count.getScope() == CountScope.CATEGORY ? categoryIds : null)
                .stream().map(Stock::getMaterialSkuId).toList();
    }

    /** 手选的 SKU：账面数 = 现在的库存；期初录入是 0，已经有库存的不能进 */
    private Map<Long, Book> pickedBooks(InvCount count, List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        materialService.skuRefs(skuIds);
        Map<Long, Book> stock = books(count, skuIds);
        if (count.getPurpose() == CountPurpose.OPENING) {
            List<Long> stocked = skuIds.stream().filter(k -> stock.get(k).qty().signum() != 0).toList();
            if (!stocked.isEmpty()) {
                Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(stocked);
                throw new BizException("这些物料在这个仓已经有库存，期初录入只录还没有库存的，有库存的请用日常盘点："
                        + stocked.stream().map(k -> briefs.get(k).getSkuCode()).collect(Collectors.joining("、")));
            }
        }
        return stock;
    }

    private record Book(BigDecimal qty, long revision) {
    }

    /** qty和revision一次在同一键锁内取得；期初也先读真实余额，检查已有库存后才存账面0。 */
    private Map<Long, Book> books(InvCount count, Collection<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Stock> stock = ledger.lockBalances(count.getWarehouseId(), skuIds);
        Map<Long, Book> out = new LinkedHashMap<>();
        skuIds.forEach(k -> out.put(k, new Book(stock.get(k).getQty(), stock.get(k).getMovementRevision())));
        return out;
    }

    private static InvCountItem newItem(long countId, int seq, long skuId, Book book) {
        InvCountItem item = new InvCountItem();
        item.setCountId(countId);
        item.setSeq(seq);
        item.setMaterialSkuId(skuId);
        item.setOwnerType(Stock.OWN);
        item.setOwnerId(0L);
        item.setBookQty(book.qty());
        item.setBookRevision(book.revision());
        return item;
    }

    /** 单头的版本号 +1（存草稿、加物料、导回、重新取账面数都改它）；状态不是草稿了 1002 */
    private void bump(InvCount count, LambdaUpdateWrapper<InvCount> more) {
        LambdaUpdateWrapper<InvCount> w = (more == null ? new LambdaUpdateWrapper<InvCount>() : more)
                .setSql("version = version + 1")
                .eq(InvCount::getId, count.getId())
                .eq(InvCount::getStatus, DRAFT)
                .eq(InvCount::getVersion, count.getVersion());
        if (countMapper.update(new InvCount(), w) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张单已经被别人改过或者提交了，请刷新后再改");
        }
    }

    @Override
    @Transactional
    public int update(long id, CountSaveReq req) {
        InvCount count = lock(id, req.getVersion());
        FLOW.next("edit", count.getStatus());
        if (!Objects.equals(count.getCounterId(), req.getCounterId())) {
            checkCounter(req.getCounterId());
        }
        Map<Long, InvCountItem> bySku = itemMapper.selectByCount(id).stream()
                .collect(Collectors.toMap(InvCountItem::getMaterialSkuId, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (CountSaveReq.Item r : req.getItems()) {
            if (!bySku.containsKey(r.getMaterialSkuId())) {
                throw new BizException(400, "有物料不在这张单上，加物料请用「加物料」");
            }
            if (!seen.add(r.getMaterialSkuId())) {
                throw new BizException(400, "同一种物料写了两行");
            }
        }
        Set<Long> posted = stockLogMapper.postedCountItems(id);
        List<InvCountItem> removed = bySku.values().stream().filter(i -> !seen.contains(i.getMaterialSkuId())).toList();
        for (InvCountItem r : removed) {
            if (posted.contains(r.getId())) {
                throw new BizException("第 " + r.getSeq() + " 行过过账，不能删（实盘数可以改）");
            }
        }
        bump(count, new LambdaUpdateWrapper<InvCount>()
                .set(InvCount::getCountDate, req.getCountDate())
                .set(InvCount::getCounterId, req.getCounterId())
                .set(InvCount::getRemark, InvTexts.blankToNull(req.getRemark())));
        if (!removed.isEmpty()) {
            itemMapper.deleteByIds(removed.stream().map(InvCountItem::getId).toList());
        }
        for (CountSaveReq.Item r : req.getItems()) {
            itemMapper.update(new InvCountItem(), new LambdaUpdateWrapper<InvCountItem>()
                    .set(InvCountItem::getCountedQty, r.getCountedQty())
                    .set(InvCountItem::getRemark, InvTexts.blankToNull(r.getRemark()))
                    .eq(InvCountItem::getId, bySku.get(r.getMaterialSkuId()).getId()));
        }
        docLogService.add(BizType.INV_COUNT, id, "update", null);
        return req.getVersion() + 1;
    }

    @Override
    @Transactional
    public CountDetailVO addItems(long id, CountAddItemsReq req) {
        InvCount count = lock(id, req.version());
        FLOW.next("edit", count.getStatus());
        List<InvCountItem> existing = itemMapper.selectByCount(id);
        Set<Long> have = existing.stream().map(InvCountItem::getMaterialSkuId).collect(Collectors.toSet());
        List<Long> skuIds = req.skuIds().stream().filter(Objects::nonNull).distinct().filter(k -> !have.contains(k)).toList();
        Map<Long, Book> books = pickedBooks(count, skuIds);
        int seq = existing.stream().mapToInt(InvCountItem::getSeq).max().orElse(0);
        List<InvCountItem> added = new ArrayList<>();
        for (Map.Entry<Long, Book> e : books.entrySet()) {
            added.add(newItem(id, ++seq, e.getKey(), e.getValue()));
        }
        bump(count, null);
        if (!added.isEmpty()) {
            itemMapper.insert(added);
        }
        docLogService.add(BizType.INV_COUNT, id, "add-items", added.size() + " 种");
        return detail(countMapper.selectById(id));
    }

    /**
     * 重新取账面数：取账面以后有过进出的行（和历史上没记变动次数的行）在同键锁内重拍账面数、变动次数，清空实盘数，要重数；
     * 没动过的行实盘数留着。全部、按分类的再把范围里新出现的 SKU 补进来。已有的行一行不删（手加的、过过账的都留着）
     */
    @Override
    @Transactional
    public CountDetailVO refreshBook(long id, DocActionReq req) {
        InvCount count = lock(id, req.version());
        FLOW.next("refreshBook", count.getStatus());
        List<InvCountItem> existing = itemMapper.selectByCount(id);
        Set<Long> allSkus = existing.stream().map(InvCountItem::getMaterialSkuId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (count.getScope() != CountScope.PICKED) {
            allSkus.addAll(scopeSkus(count, categoryMapper.categoryIds(id)));
        }
        // 先收集全部键再一次排序加锁；新范围可能含比原明细更小的SKU，不能分两次倒序锁。
        Map<Long, Book> stock = books(count, allSkus);
        boolean opening = count.getPurpose() == CountPurpose.OPENING;
        int moved = 0;
        for (InvCountItem i : existing) {
            Book book = stock.get(i.getMaterialSkuId());
            if (opening && book.qty().signum() != 0) {
                throw new BizException("第 " + i.getSeq() + " 行已经有库存，请用日常盘点，不能重新录期初");
            }
            if (i.getBookRevision() != null && i.getBookRevision() == book.revision()) {
                continue;
            }
            itemMapper.update(new InvCountItem(), new LambdaUpdateWrapper<InvCountItem>()
                    .set(InvCountItem::getBookQty, opening ? BigDecimal.ZERO : book.qty())
                    .set(InvCountItem::getBookRevision, book.revision())
                    .set(InvCountItem::getCountedQty, null).eq(InvCountItem::getId, i.getId()));
            moved++;
        }
        int added = 0;
        if (count.getScope() != CountScope.PICKED) {
            Set<Long> have = existing.stream().map(InvCountItem::getMaterialSkuId).collect(Collectors.toSet());
            int seq = existing.stream().mapToInt(InvCountItem::getSeq).max().orElse(0);
            List<InvCountItem> more = new ArrayList<>();
            for (Map.Entry<Long, Book> e : stock.entrySet()) {
                if (!have.contains(e.getKey())) {
                    more.add(newItem(id, ++seq, e.getKey(), e.getValue()));
                }
            }
            if (!more.isEmpty()) {
                itemMapper.insert(more);
            }
            added = more.size();
        }
        bump(count, new LambdaUpdateWrapper<InvCount>().set(InvCount::getBookTime, OffsetDateTime.now(clock)));
        List<String> notes = new ArrayList<>();
        if (moved > 0) {
            notes.add(moved + " 行有进出，已重拍、要重数");
        }
        if (added > 0) {
            notes.add("新带出 " + added + " 种");
        }
        docLogService.add(BizType.INV_COUNT, id, "refresh-book", notes.isEmpty() ? null : String.join("；", notes));
        return detail(countMapper.selectById(id));
    }

    // ---------- 状态动作 ----------

    @Override
    @Transactional
    public void act(long id, String action, DocActionReq req) {
        InvCount count = getVisible(id);
        switch (action) {
            case "submit" -> submit(count, req);
            case "withdraw" -> {
                if (!Objects.equals(count.getCreateBy(), LoginUser.currentUserId())) {
                    throw new BizException(403, "只有制单人能撤回");
                }
                change(count, "withdraw", req);
            }
            case "reject" -> {
                if (req.reason() == null || req.reason().isBlank()) {
                    throw new BizException(400, "请写驳回原因");
                }
                change(count, "reject", req);
            }
            case "audit" -> audit(count, req);
            case "unaudit" -> unaudit(count, req);
            case "void" -> {
                if (!stockLogMapper.countPosted(id)) {
                    throw new BizException("这张单没过过账，直接删除就行");
                }
                change(count, "void", req);
            }
            default -> throw new BizException(404, "盘点单没有动作 " + action);
        }
    }

    /** 一次状态转换：比较并交换 + 版本号 +1 + 操作记录；审核记审核人，反审核清掉 */
    private void change(InvCount count, String action, DocActionReq req) {
        DocStatus to = FLOW.next(action, count.getStatus());
        LambdaUpdateWrapper<InvCount> cas = StatusCas.of(InvCount::getId, count.getId(), InvCount::getStatus, count.getStatus(), to)
                .setSql("version = version + 1")
                .eq(InvCount::getVersion, req.version());
        if (action.equals("audit")) {
            cas.set(InvCount::getAuditBy, LoginUser.currentUserId()).set(InvCount::getAuditTime, OffsetDateTime.now(clock));
        } else if (action.equals("unaudit")) {
            cas.set(InvCount::getAuditBy, null).set(InvCount::getAuditTime, null);
        }
        StatusCas.update(countMapper, new InvCount(), cas);
        docLogService.add(BizType.INV_COUNT, count.getId(), action, count.getStatus(), to, InvTexts.blankToNull(req.reason()));
    }

    private void submit(InvCount count, DocActionReq req) {
        count = lock(count.getId(), req.version());
        FLOW.next("submit", count.getStatus());
        List<InvCountItem> items = itemMapper.selectByCount(count.getId());
        if (items.isEmpty()) {
            throw new BizException("明细一行都没有，先加物料");
        }
        long unfilled = items.stream().filter(i -> i.getCountedQty() == null).count();
        if (unfilled > 0) {
            throw new BizException("还有 " + unfilled + " 行没填实盘数");
        }
        // 取账面以后有过进出（哪怕一进一出净数不变），这一行数的可能是进出前或进出后的货，差异算不准
        Map<Long, Stock> balances = ledger.lockBalances(count.getWarehouseId(), items.stream().map(InvCountItem::getMaterialSkuId).toList());
        List<InvCountItem> stale = items.stream().filter(i -> i.getBookRevision() == null
                || !Objects.equals(i.getBookRevision(), balances.get(i.getMaterialSkuId()).getMovementRevision())).toList();
        if (!stale.isEmpty()) {
            Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(stale.stream().map(InvCountItem::getMaterialSkuId).toList());
            List<String> rows = stale.stream().map(i -> "第 " + i.getSeq() + " 行 " + briefs.get(i.getMaterialSkuId()).getSkuCode()).toList();
            throw new BizException(ErrorCodes.STATE_CHANGED, String.join("、", rows.subList(0, Math.min(rows.size(), 5)))
                    + (rows.size() > 5 ? " 等 " + rows.size() + " 行" : "")
                    + " 取账面以后有过进出：点\"重新取账面数\"，重数这几行再提交（别的行不用重数）", rows);
        }
        change(count, "submit", req);
    }

    /** 审核过账：差异不为 0 的行把差异加到现在的库存上（调完是负的 1001） */
    private void audit(InvCount count, DocActionReq req) {
        change(count, "audit", req);
        List<InvCountItem> items = itemMapper.selectByCount(count.getId());
        if (items.stream().anyMatch(i -> i.getBookRevision() == null)) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张单是升级前提交的，没记取账面时的库存变动：先驳回或撤回，重新取账面数、重数后再提交");
        }
        ledger.post(posting(count, items));
    }

    /**
     * 反审核：调整退回去（退完是负的 1001），回到草稿。这个仓已经开了另一张盘点单的不行（回草稿就是两张没审核的）。
     * 实盘数留着：提交时确认的差异还是对的（提交以后的进出账上、货上都有），所以把变动次数对齐到现在，
     * 改错一个数再提交不用全部重数；回草稿以后再有进出的行，提交时照样拦
     */
    private void unaudit(InvCount count, DocActionReq req) {
        FLOW.next("unaudit", count.getStatus());
        checkNoOpenCount(count.getWarehouseId(), count.getId());
        change(count, "unaudit", req);
        List<InvCountItem> items = itemMapper.selectByCount(count.getId());
        ledger.post(posting(count, items).reversed());
        Map<Long, Stock> balances = ledger.lockBalances(count.getWarehouseId(), items.stream().map(InvCountItem::getMaterialSkuId).toList());
        for (InvCountItem i : items) {
            itemMapper.update(new InvCountItem(), new LambdaUpdateWrapper<InvCountItem>()
                    .set(InvCountItem::getBookRevision, balances.get(i.getMaterialSkuId()).getMovementRevision())
                    .eq(InvCountItem::getId, i.getId()));
        }
    }

    /** 盘点单的库存变动：一行一笔，调整量 = 实盘 − 账面；日志记盘盈、盘亏，期初录入记期初 */
    static StockLedger.Posting posting(InvCount count, List<InvCountItem> items) {
        List<StockLedger.Move> moves = new ArrayList<>();
        for (InvCountItem i : items) {
            BigDecimal diff = i.getCountedQty().subtract(i.getBookQty());
            if (diff.signum() == 0) {
                continue;
            }
            StockChangeType type = count.getPurpose() == CountPurpose.OPENING ? StockChangeType.OPENING
                    : diff.signum() > 0 ? StockChangeType.COUNT_GAIN : StockChangeType.COUNT_LOSS;
            moves.add(new StockLedger.Move(count.getWarehouseId(), i.getMaterialSkuId(), diff, type, i.getSeq(), null, i.getId(), null));
        }
        return new StockLedger.Posting(null, count.getId(), count.getNo(), count.getCountDate(), moves);
    }

    @Override
    @Transactional
    public void delete(long id) {
        InvCount count = countMapper.lockById(id);
        if (count == null) {
            throw new BizException(404, "单据不存在");
        }
        DataScope.current(false).check(count.getCreateBy(), count.getCounterId());
        FLOW.next("remove", count.getStatus());
        if (stockLogMapper.countPosted(id)) {
            throw new BizException("这张单过过账（库存日志里有它），不能删，只能作废");
        }
        categoryMapper.deleteByCount(id);
        itemMapper.delete(new LambdaQueryWrapper<InvCountItem>()
                .eq(InvCountItem::getCountId, id));
        attachmentService.deleteByDoc(BizType.INV_COUNT, id);
        docLogService.deleteByDoc(BizType.INV_COUNT, id);
        countMapper.deleteById(id);
    }

    // ---------- 盘点表、导回、导出 ----------

    @Override
    public byte[] sheet(long id) {
        byte[] bytes = sheetPlan(id).toBytes();
        recordSheetExport(id);
        return bytes;
    }

    @Override
    public ExcelExport sheetPlan(long id) {
        getVisible(id);
        com.lingmao.scm.common.excel.BoundedExportRows.requireWithinLimit(
                itemMapper.countExportRows(id, Excel.MAX_EXPORT_ROWS + 1), Excel.MAX_EXPORT_ROWS, "盘点表");
        var units = excel.units();
        return Excel.exportBook().sheet(excel.sheetSchema(), sink -> {
            int total = 0;
            for (int page = 1; ; page++) {
                sink.flush();
                List<InvCountItem> items = itemMapper.selectPage(new Page<>(page, 500, false),
                        new LambdaQueryWrapper<InvCountItem>().eq(InvCountItem::getCountId, id)
                                .orderByAsc(InvCountItem::getSeq, InvCountItem::getId)).getRecords();
                total += items.size();
                com.lingmao.scm.common.excel.BoundedExportRows.requireWithinLimit(total, Excel.MAX_EXPORT_ROWS, "盘点表");
                sink.flush();
                var briefs = materialService.skuBriefs(items.stream().map(InvCountItem::getMaterialSkuId).toList());
                for (var row : excel.sheetRows(items, briefs, units)) sink.row(row);
                if (items.size() < 500) break;
            }
        });
    }

    /** 预检完成、发送响应前独立提交审计，不在导出只读快照事务里写入。 */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSheetExport(long id) { docLogService.add(BizType.INV_COUNT, id, "export-sheet", null); }

    /**
     * 导回实盘数（docs/api/04 §3.5.11）：按 SKU 码对上行，填实盘数和备注；实盘数空着的跳过（不清掉已填的）；
     * 表里有、单上没有的加一行（账面数取现在的库存，期初录入是 0、有库存的不行）。有一处不对整批不导（1003）
     */
    @Override
    public ImportResult importSheet(long id, MultipartFile file) {
        InvCount count = getVisible(id);
        FLOW.next("edit", count.getStatus());
        return imports.execute("count", "count:" + id, "inventory:count:import", file, Map.of("companyId", count.getCompanyId()),
                () -> excel.read(file), rows -> writeImport(count, rows));
    }

    private ImportResult writeImport(InvCount count, ExcelImport<CountExcel.SheetRow> rows) {
        long id = count.getId();
        rows.checkUnique(CountServiceImpl::codeOf, "SKU 码");
        Map<String, Long> skuIds = materialService.skuIdsByCodes(rows.lines().stream().map(l -> codeOf(l.data()))
                .filter(Objects::nonNull).toList());
        List<InvCountItem> existing = itemMapper.selectByCount(id);
        Map<Long, InvCountItem> bySku = existing.stream().collect(Collectors.toMap(InvCountItem::getMaterialSkuId, Function.identity()));

        List<Long> newSkus = new ArrayList<>();
        Map<Long, Integer> newRowOf = new LinkedHashMap<>();
        int skipped = 0;
        for (ExcelImport.Line<CountExcel.SheetRow> line : rows.lines()) {
            String code = codeOf(line.data());
            Long skuId = code == null ? null : skuIds.get(code);
            if (code == null) {
                continue;
            } else if (skuId == null) {
                rows.error(line.row(), "SKU 码", "物料库里没有这个 SKU 码");
            } else if (line.data().getCountedQty() == null) {
                skipped++;
            } else if (!bySku.containsKey(skuId) && !newRowOf.containsKey(skuId)) {
                newSkus.add(skuId);
                newRowOf.put(skuId, line.row());
            }
        }
        InvCount locked = lock(id, count.getVersion());
        FLOW.next("edit", locked.getStatus());
        Map<Long, Book> stock = books(locked, newSkus);
        if (count.getPurpose() == CountPurpose.OPENING) {
            newRowOf.forEach((skuId, row) -> {
                if (stock.get(skuId).qty().signum() != 0) {
                    rows.error(row, "SKU 码", "这个仓已经有它的库存，期初录入只录还没有库存的，请用日常盘点");
                }
            });
        }
        rows.dataOrThrow();

        int seq = existing.stream().mapToInt(InvCountItem::getSeq).max().orElse(0);
        Map<Long, InvCountItem> added = new LinkedHashMap<>();
        for (Long skuId : newSkus) {
            added.put(skuId, newItem(id, ++seq, skuId, stock.get(skuId)));
        }
        if (!added.isEmpty()) {
            itemMapper.insert(new ArrayList<>(added.values()));
        }
        int filled = 0;
        for (ExcelImport.Line<CountExcel.SheetRow> line : rows.lines()) {
            CountExcel.SheetRow r = line.data();
            if (r.getCountedQty() == null) {
                continue;
            }
            Long skuId = skuIds.get(codeOf(r));
            InvCountItem item = bySku.containsKey(skuId) ? bySku.get(skuId) : added.get(skuId);
            // 新行是在本次导入才拍账，文件里的旧计数不属于这个测量窗口。
            if (count.getPurpose() == CountPurpose.DAILY && added.containsKey(skuId)) {
                rows.note(line.row(), "实盘数", "新加物料已取账面；请从现在开始重新数货后填写或重新导回，旧实盘数未沿用");
                continue;
            }
            itemMapper.update(new InvCountItem(), new LambdaUpdateWrapper<InvCountItem>()
                    .set(InvCountItem::getCountedQty, r.getCountedQty())
                    .set(InvCountItem::getRemark, InvTexts.blankToNull(r.getRemark()))
                    .eq(InvCountItem::getId, item.getId()));
            filled++;
        }
        bump(locked, null);
        if (!added.isEmpty()) {
            rows.note(0, null, "新加了 " + added.size() + " 种物料");
        }
        if (skipped > 0) {
            rows.note(0, null, skipped + " 行实盘数是空的，跳过了");
        }
        docLogService.add(BizType.INV_COUNT, id, "import", "填了 " + filled + " 行");
        return ImportResult.of(filled, rows.notes());
    }

    /** SKU 码不分大小写；没填（必填列，Excel 那边已经记了错）是 null */
    private static String codeOf(CountExcel.SheetRow r) {
        return r.getSkuCode() == null || r.getSkuCode().isBlank() ? null : r.getSkuCode().strip().toUpperCase(Locale.ROOT);
    }

    @Override
    public byte[] export(CountQuery query) { return exportPlan(query).toBytes(); }

    @Override
    public ExcelExport exportPlan(CountQuery query) {
        tagFilters.prepare(query);
        var codes = Keywords.codes(query.getKeyword());
        var dataScope = DataScope.current(query.isMine());
        return Excel.exportBook().sheet(excel.listSchema(), sink -> {
            var scope = com.lingmao.scm.common.scope.ExportScope.accumulate(query.getIds(), CountRowVO::getId, CountRowVO::getCompanyId);
            int total = 0;
            for (int page = 1; ; page++) {
                sink.flush();
                List<CountRowVO> rows = countMapper.selectRowPage(new Page<>(page, 500, false), query, codes, dataScope).getRecords();
                scope.accept(rows);
                total += rows.size();
                com.lingmao.scm.common.excel.BoundedExportRows.requireWithinLimit(total, Excel.MAX_EXPORT_ROWS, "物料盘点单");
                sink.flush();
                var names = userService.nicknames(rows.stream().map(CountRowVO::getCounterId).collect(Collectors.toSet()));
                rows.forEach(row -> {
                    row.setCounterName(names.get(row.getCounterId()));
                    row.setScopeText(scopeText(row.getScope(), row.getCategoryNames(), row.getItemCount()));
                });
                for (var row : excel.listRows(rows)) sink.row(row);
                if (rows.size() < 500) break;
            }
            scope.finish();
        });
    }
}
