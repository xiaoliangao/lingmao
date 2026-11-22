package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportError;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.inventory.dto.CountCreateReq;
import com.lingmao.scm.module.inventory.dto.CountCreatedVO;
import com.lingmao.scm.module.inventory.dto.CountDetailVO;
import com.lingmao.scm.module.inventory.dto.CountItemVO;
import com.lingmao.scm.module.inventory.dto.CountQuery;
import com.lingmao.scm.module.inventory.dto.CountRowVO;
import com.lingmao.scm.module.inventory.dto.CountSaveReq;
import com.lingmao.scm.module.inventory.dto.InvDocCreateReq;
import com.lingmao.scm.module.inventory.dto.InvDocDetailVO;
import com.lingmao.scm.module.inventory.dto.InvDocItemReq;
import com.lingmao.scm.module.inventory.dto.InvDocQuery;
import com.lingmao.scm.module.inventory.dto.InvDocRowVO;
import com.lingmao.scm.module.inventory.dto.InvDocSaveReq;
import com.lingmao.scm.module.inventory.dto.StockLogQuery;
import com.lingmao.scm.module.inventory.dto.StockQuery;
import com.lingmao.scm.module.inventory.dto.StockShortRow;
import com.lingmao.scm.module.inventory.entity.CountPurpose;
import com.lingmao.scm.module.inventory.entity.CountScope;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.service.CountService;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.module.inventory.service.StockService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存在真库上（架构 §5.2）：审核过账改库存、记日志；反审核反过来再记一条；库存不够整单回滚、缺的一起报；
 * 两张单同时扣同一个 SKU 只成一张（条件更新）、反着顺序扣两个 SKU 不死锁（排序）；过过账的草稿只能作废（外键拦着删）；
 * 盘点按差异调到现在的库存上、期初录入、一个仓只能有一张没审核的（部分唯一索引）
 */
@IntegrationTest
class InventoryIT {

    private static final long USER = 900_401L;
    private static final List<String> PERMS = List.of("inventory:doc:list", "inventory:doc:add", "inventory:doc:edit",
            "inventory:doc:remove", "inventory:doc:audit", "inventory:count:list", "inventory:count:add", "inventory:count:edit",
            "inventory:count:remove", "inventory:count:submit", "inventory:count:audit", "inventory:count:import", "techpack:cost:query");

    @Autowired
    private InvDocService invDocService;
    @Autowired
    private CountService countService;
    @Autowired
    private StockService stockService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void login() {
        TestIdentity.grant(jdbcTemplate, USER, PERMS);
        loginAs();
    }

    @AfterEach
    void logout() {
        TestIdentity.clear();
    }

    private static void loginAs() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(USER, "jti-it"), null, PERMS.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    /**
     * 规则（docs/api/04 §3.2.6、§3.2.7）：其他入库 10 → 其他出库 4，库存 6，每笔一条日志、结存对得上；
     * 先反审核入库单会让库存变负（1001），整张单不动；先退出库单再退入库单，库存回到 0，日志 4 条一条不删。
     * 过过账的草稿：删 → 1000 只能作废，按钮给"作废"；作废后只读。没过过账的草稿直接删
     */
    @Test
    void postUnauditAndVoid() {
        Fixture f = fixture();
        long in = create(f, InvDocType.OTHER_IN, null, f.warehouseA, f.skuA, "10");
        audit(in);
        long out = create(f, InvDocType.OTHER_OUT, f.warehouseA, null, f.skuA, "4");
        audit(out);
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("6");
        assertThat(jdbcTemplate.queryForList("SELECT balance_qty FROM inv_stock_log WHERE material_sku_id = ? ORDER BY id",
                BigDecimal.class, f.skuA)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10"), new BigDecimal("6"));

        assertThatThrownBy(() -> invDocService.act(in, "unaudit", new DocActionReq(version(in), null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STOCK_SHORTAGE));
        assertThat(invDocService.detail(in).getStatus()).isEqualTo("APPROVED");
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("6");

        invDocService.act(out, "unaudit", new DocActionReq(version(out), null));
        invDocService.act(in, "unaudit", new DocActionReq(version(in), "录错了"));
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("0");
        assertThat(count("SELECT COUNT(*) FROM inv_stock_log WHERE material_sku_id = ?", f.skuA)).isEqualTo(4);

        InvDocDetailVO back = invDocService.detail(in);
        assertThat(back.getCompanyName()).startsWith("IT 主体");
        assertThat(back.getStatus()).isEqualTo("DRAFT");
        assertThat(back.isPosted()).isTrue();
        assertThat(back.getActions()).containsExactly("edit", "audit", "void");
        assertThatThrownBy(() -> invDocService.delete(in)).isInstanceOf(BizException.class).hasMessageContaining("只能作废");
        // 过过账的行不能删（库存日志指着它），数量能改
        InvDocSaveReq save = saveReq(back, List.of());
        assertThatThrownBy(() -> invDocService.update(in, save)).isInstanceOf(BizException.class);
        InvDocItemReq row = itemReq(f.skuA, "12");
        row.setId(back.getItems().getFirst().getId());
        int v = invDocService.update(in, saveReq(back, List.of(row)));
        invDocService.act(in, "void", new DocActionReq(v, "重复录入"));
        assertThat(invDocService.detail(in).getStatus()).isEqualTo("VOIDED");
        assertThat(invDocService.detail(in).getActions()).isEmpty();

        long draft = create(f, InvDocType.OTHER_IN, null, f.warehouseA, f.skuA, "1");
        assertThat(invDocService.detail(draft).getActions()).containsExactly("edit", "audit", "remove");
        invDocService.delete(draft);
        assertThat(count("SELECT COUNT(*) FROM inv_doc WHERE id = ?", draft)).isZero();
    }

    /** 规则（§3.4.1）：调拨两种料都不够，整张单都试一遍、两种一起报 1001；事务回滚，库存、状态都没动 */
    @Test
    void shortageRollsBackAndListsAll() {
        Fixture f = fixture();
        long in = create(f, InvDocType.OTHER_IN, null, f.warehouseA, f.skuA, "1");
        audit(in);
        InvDocCreateReq req = createReq(f, InvDocType.TRANSFER, f.warehouseA, f.warehouseB, List.of(itemReq(f.skuA, "3"), itemReq(f.skuB, "2")));
        long transfer = invDocService.create(req).id();

        assertThatThrownBy(() -> audit(transfer)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCodes.STOCK_SHORTAGE);
            assertThat(e.getData()).asList().hasSize(2);
            assertThat((StockShortRow) ((List<?>) e.getData()).getFirst()).satisfies(r -> {
                assertThat(r.stockQty()).isEqualByComparingTo("1");
                assertThat(r.shortQty()).isEqualByComparingTo("2");
            });
        });
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("1");
        assertThat(count("SELECT COUNT(*) FROM inv_stock WHERE warehouse_id = ?", f.warehouseB)).isZero();
        assertThat(invDocService.detail(transfer).getStatus()).isEqualTo("DRAFT");
        assertThat(invDocService.detail(transfer).isPosted()).isFalse();
    }

    /**
     * 规则（PRD §7.3、架构 §5.2）：库存 10，两张各出 6 的单同时审核，只成一张、另一张 1001，库存 4 不是负的；
     * 两张单反着顺序各扣两个 SKU，同时审核都成功（按仓库、SKU 排好序再改，不死锁）
     */
    @Test
    void concurrentPostings() throws Exception {
        Fixture f = fixture();
        audit(create(f, InvDocType.OTHER_IN, null, f.warehouseA, f.skuA, "10"));
        audit(create(f, InvDocType.OTHER_IN, null, f.warehouseA, f.skuB, "10"));

        long out1 = create(f, InvDocType.OTHER_OUT, f.warehouseA, null, f.skuA, "6");
        long out2 = create(f, InvDocType.OTHER_OUT, f.warehouseA, null, f.skuA, "6");
        List<Object> results = race(() -> tryAudit(out1), () -> tryAudit(out2));
        assertThat(results).containsExactlyInAnyOrder("ok", ErrorCodes.STOCK_SHORTAGE);
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("4");

        long ab = invDocService.create(createReq(f, InvDocType.OTHER_OUT, f.warehouseA, null,
                List.of(itemReq(f.skuA, "1"), itemReq(f.skuB, "1")))).id();
        long ba = invDocService.create(createReq(f, InvDocType.OTHER_OUT, f.warehouseA, null,
                List.of(itemReq(f.skuB, "1"), itemReq(f.skuA, "1")))).id();
        assertThat(race(() -> tryAudit(ab), () -> tryAudit(ba))).containsExactly("ok", "ok");
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("2");
        assertThat(stock(f.warehouseA, f.skuB)).isEqualByComparingTo("8");
    }

    /**
     * 规则（PRD §6.6.8）：期初录入把还没有库存的 SKU 录进去（日志记期初），已经有库存的再进期初录入 1000；
     * 日常盘点拍下账面数以后又出了 2，审核时只调差异（实盘 − 账面），加到现在的库存上；
     * 一个仓同时只能有一张没审核的（Service 拦 1000，绕过 Service 部分唯一索引拦）；反审核把调整退回去，过过账的草稿只能作废
     */
    @Test
    void countFlow() {
        Fixture f = fixture();
        CountCreatedVO opening = countService.create(countReq(f, CountPurpose.OPENING, List.of(f.skuA)));
        assertThat(opening.itemCount()).isEqualTo(1);
        fill(opening.id(), "20");
        submitAndAudit(opening.id());
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("20");
        assertThat(jdbcTemplate.queryForObject("SELECT change_type FROM inv_stock_log WHERE count_id = ?", String.class, opening.id()))
                .isEqualTo("OPENING");
        assertThatThrownBy(() -> countService.create(countReq(f, CountPurpose.OPENING, List.of(f.skuA))))
                .isInstanceOf(BizException.class).hasMessageContaining("已经有库存");

        CountCreateReq dailyReq = countReq(f, CountPurpose.DAILY, List.of());
        dailyReq.setScope(CountScope.ALL);
        CountCreatedVO daily = countService.create(dailyReq);
        assertThat(countService.detail(daily.id()).getItems()).singleElement()
                .satisfies(i -> assertThat(i.getBookQty()).isEqualByComparingTo("20"));
        assertThatThrownBy(() -> countService.create(dailyReq)).isInstanceOf(BizException.class).hasMessageContaining(daily.no());
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO inv_count (no, company_id, warehouse_id, count_date, counter_id) VALUES (?, ?, ?, CURRENT_DATE, ?)""",
                "PDIT" + ThreadLocalRandom.current().nextInt(1_000_000), f.company, f.warehouseA, f.counter))
                .isInstanceOf(DuplicateKeyException.class);

        fill(daily.id(), "17");
        CountDetailVO measured = countService.detail(daily.id());
        countService.act(daily.id(), "submit", new DocActionReq(measured.getVersion(), null));
        audit(create(f, InvDocType.OTHER_OUT, f.warehouseA, null, f.skuA, "2"));
        countService.act(daily.id(), "audit", new DocActionReq(measured.getVersion() + 1, null));
        // 账面20、实盘17已在提交时确认；提交以后的出库2不改变固定差额：20−2−3=15
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("15");
        CountDetailVO approved = countService.detail(daily.id());
        assertThat(approved.getLossCount()).isEqualTo(1);
        assertThat(approved.getItems().getFirst().getAdjustedQty()).isEqualByComparingTo("-3");

        countService.act(daily.id(), "unaudit", new DocActionReq(approved.getVersion(), null));
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("18");
        CountDetailVO back = countService.detail(daily.id());
        assertThat(back.isPosted()).isTrue();
        assertThat(back.getActions()).contains("void").doesNotContain("remove");
        assertThatThrownBy(() -> countService.delete(daily.id())).isInstanceOf(BizException.class).hasMessageContaining("只能作废");
        countService.act(daily.id(), "void", new DocActionReq(back.getVersion(), null));
        // 作废的不算没审核的：这个仓能再开一张
        assertThat(countService.create(dailyReq).itemCount()).isEqualTo(1);
    }

    /** 取账面以后一进一出（净数不变）也要拦；重新取账面只重拍动过的行，别的行实盘留着；反审核不清实盘，改完能直接再提交 */
    @Test
    void countMeasurementRejectsAbaAndRefreshResetsOnlyMovedRows() {
        Fixture f = fixture();
        audit(create(f, InvDocType.OTHER_IN, null, f.warehouseA, f.skuA, "10"));
        audit(create(f, InvDocType.OTHER_IN, null, f.warehouseA, f.skuB, "5"));
        long id = countService.create(countReq(f, CountPurpose.DAILY, List.of(f.skuA, f.skuB))).id();
        fill(id, Map.of(f.skuA, "9", f.skuB, "4"));
        long before = item(countService.detail(id), f.skuA).getBookRevision();
        audit(create(f, InvDocType.OTHER_IN, null, f.warehouseA, f.skuA, "1"));
        audit(create(f, InvDocType.OTHER_OUT, f.warehouseA, null, f.skuA, "1"));
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("10");
        int version = countService.detail(id).getVersion();
        assertThatThrownBy(() -> countService.act(id, "submit", new DocActionReq(version, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(1002);
                    assertThat((List<?>) e.getData()).hasSize(1);
                });

        CountDetailVO refreshed = countService.refreshBook(id, new DocActionReq(version, null));
        assertThat(item(refreshed, f.skuA)).satisfies(i -> {
            assertThat(i.getCountedQty()).isNull();
            assertThat(i.getBookRevision()).isGreaterThan(before);
        });
        assertThat(item(refreshed, f.skuB).getCountedQty()).isEqualByComparingTo("4");
        fill(id, Map.of(f.skuA, "9", f.skuB, "4"));
        submitAndAudit(id);
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("9");
        assertThat(stock(f.warehouseA, f.skuB)).isEqualByComparingTo("4");

        countService.act(id, "unaudit", new DocActionReq(countService.detail(id).getVersion(), null));
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("10");
        assertThat(countService.detail(id).getItems()).allSatisfy(i -> {
            assertThat(i.getCountedQty()).isNotNull();
            assertThat(i.getBookRevision()).isNotNull();
        });
        // 反审核以后改一个数直接再提交：B 少数了一件
        fill(id, Map.of(f.skuA, "9", f.skuB, "3"));
        submitAndAudit(id);
        assertThat(stock(f.warehouseA, f.skuA)).isEqualByComparingTo("9");
        assertThat(stock(f.warehouseA, f.skuB)).isEqualByComparingTo("3");
    }

    @Test
    void legacyPendingCountCannotPostWithoutMeasurementWatermark() {
        Fixture f = fixture();
        long id = countService.create(countReq(f, CountPurpose.DAILY, List.of(f.skuA))).id();
        fill(id, "1");
        countService.act(id, "submit", new DocActionReq(countService.detail(id).getVersion(), null));
        jdbcTemplate.update("UPDATE inv_count_item SET book_revision=NULL WHERE count_id=?", id);
        int version = countService.detail(id).getVersion();
        assertThatThrownBy(() -> countService.act(id, "audit", new DocActionReq(version, null)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(1002);
        assertThat(countService.detail(id).getStatus()).isEqualTo("PENDING");
        assertThat(count("SELECT COUNT(*) FROM inv_stock_log WHERE count_id=?", id)).isZero();
    }

    /** 同明细2元→反审→改3元重审，历史事件不跟着改单；当前单位也不能冒充历史单位。 */
    @Test
    void stockValuationRemainsImmutableAcrossRepriceAndReversal() {
        Fixture f = fixture();
        InvDocItemReq initial = itemReq(f.skuA, "5"); initial.setPrice(new BigDecimal("2"));
        long id = invDocService.create(createReq(f, InvDocType.OTHER_IN, null, f.warehouseA, List.of(initial))).id();
        audit(id);
        long original = jdbcTemplate.queryForObject("SELECT id FROM inv_stock_log WHERE inv_doc_id=?", Long.class, id);
        invDocService.act(id, "unaudit", new DocActionReq(version(id), null));
        InvDocDetailVO draft = invDocService.detail(id);
        InvDocItemReq revised = itemReq(f.skuA, "5"); revised.setId(draft.getItems().getFirst().getId()); revised.setPrice(new BigDecimal("3"));
        invDocService.update(id, saveReq(draft, List.of(revised)));
        audit(id);
        assertThat(jdbcTemplate.queryForList("SELECT unit_price FROM inv_stock_log WHERE inv_doc_id=? ORDER BY id", BigDecimal.class, id))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("3"));
        assertThat(jdbcTemplate.queryForList("SELECT amount FROM inv_stock_log WHERE inv_doc_id=? ORDER BY id", BigDecimal.class, id))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("10"), new BigDecimal("-10"), new BigDecimal("15"));
        assertThat(jdbcTemplate.queryForObject("SELECT reversal_of_log_id FROM inv_stock_log WHERE inv_doc_id=? AND change_qty<0", Long.class, id))
                .isEqualTo(original);
    }

    @Test
    void legacyUnknownValuationIsNotReconstructedFromCurrentMasterData() {
        Fixture f = fixture();
        long id = create(f, InvDocType.OTHER_IN, null, f.warehouseA, f.skuA, "5");
        // 迁移前的初始事件没有估值快照；用 INSERT 建旧夹具，不更新受保护的历史流水。
        jdbcTemplate.update("UPDATE inv_doc SET status='APPROVED',version=version+1 WHERE id=?", id);
        jdbcTemplate.update("INSERT INTO inv_stock(warehouse_id,material_sku_id,qty) VALUES (?,?,5)", f.warehouseA, f.skuA);
        long legacy = jdbcTemplate.queryForObject("""
                INSERT INTO inv_stock_log(inv_doc_id,inv_doc_item_id,change_type,doc_no,biz_date,
                        warehouse_id,material_sku_id,change_qty,balance_qty,valuation_status,create_by)
                SELECT d.id,i.id,'OTHER_IN',d.no,d.doc_date,d.to_warehouse_id,i.material_sku_id,
                       5,5,'UNKNOWN',d.create_by
                FROM inv_doc d JOIN inv_doc_item i ON i.doc_id=d.id WHERE d.id=? RETURNING id
                """, Long.class, id);
        StockLogQuery query = new StockLogQuery(); query.setKeyword(invDocService.detail(id).getNo());
        assertThat(stockService.logs(query).getList()).filteredOn(r -> r.getId().equals(legacy)).singleElement()
                .satisfies(r -> { assertThat(r.getStockUnit()).isNull(); assertThat(r.getUnitPrice()).isNull(); });
    }

    /**
     * 列表、导出、盘点表的 SQL 在真库上跑一遍：关键字按单号 / 物料查、几个单号精确查、数据范围；库存明细、汇总、日志；
     * 盘点表导出再原样导回（实盘数照填，空的跳过）
     */
    @Test
    void listsExportsAndSheet() {
        Fixture f = fixture();
        long in = create(f, InvDocType.OTHER_IN, null, f.warehouseA, f.skuA, "5");
        audit(in);
        String no = invDocService.detail(in).getNo();
        InvDocQuery q = new InvDocQuery();
        q.setKind(InvDocKind.IN);
        q.setKeyword("IT 库存面料");
        assertThat(invDocService.page(q).getList()).extracting(InvDocRowVO::getId).contains(in);
        q.setKeyword(no + " RK00000000000");
        assertThat(invDocService.page(q).getList()).singleElement().satisfies(r -> {
            assertThat(r.getItemCount()).isEqualTo(1);
            assertThat(r.getTotalQty()).isEqualByComparingTo("5");
            assertThat(r.getFirstItem().getSkuCode()).endsWith("-A");
            assertThat(r.getToWarehouseName()).startsWith("IT 面料仓");
            assertThat(r.getActions()).containsExactly("unaudit");
        });
        q.setStatus(List.of(DocStatus.DRAFT));
        assertThat(invDocService.page(q).getList()).isEmpty();
        q.setStatus(null);
        assertThat(invDocService.export(q)).isNotEmpty();

        StockQuery sq = new StockQuery();
        sq.setWarehouseId(f.warehouseA);
        assertThat(stockService.page(sq).getList()).singleElement().satisfies(r -> {
            assertThat(r.getQty()).isEqualByComparingTo("5");
            assertThat(r.getColorName()).startsWith("IT 色");
        });
        assertThat(stockService.summary(sq).getList()).singleElement()
                .satisfies(r -> assertThat(r.getMaterialQty()).isEqualByComparingTo("5"));
        StockLogQuery lq = new StockLogQuery();
        lq.setKeyword(no.toLowerCase() + "，CK00000000000");
        assertThat(stockService.logs(lq).getList()).hasSize(1);
        lq.setKeyword(no);
        assertThat(stockService.logs(lq).getList()).singleElement().satisfies(r -> {
            assertThat(r.getDocType()).isEqualTo("INV_DOC");
            assertThat(r.getDocKind()).isEqualTo(InvDocKind.IN);
            assertThat(r.getBalanceQty()).isEqualByComparingTo("5");
        });
        assertThat(stockService.export(sq)).isNotEmpty();
        assertThat(stockService.exportSummary(sq)).isNotEmpty();
        assertThat(stockService.exportLogs(lq)).isNotEmpty();

        CountCreateReq req = countReq(f, CountPurpose.DAILY, List.of(f.skuA, f.skuB));
        CountCreatedVO created = countService.create(req);
        CountQuery cq = new CountQuery();
        cq.setKeyword(created.no() + "\nPD00000000000");
        assertThat(countService.page(cq).getList()).extracting(CountRowVO::getId).containsExactly(created.id());
        cq.setKeyword(created.no());
        assertThat(countService.page(cq).getList()).singleElement().satisfies(r -> {
            assertThat(r.getScopeText()).isEqualTo("手选 2 种");
            assertThat(r.getCounterName()).isEqualTo("IT 盘点人");
            assertThat(r.getFilledCount()).isZero();
        });
        assertThat(countService.export(cq)).isNotEmpty();

        // 盘了一种（A 实盘 4），导出盘点表、原样导回：A 照填，B 实盘空着跳过
        CountDetailVO d = countService.detail(created.id());
        CountSaveReq save = new CountSaveReq();
        save.setVersion(d.getVersion());
        save.setCountDate(d.getCountDate());
        save.setCounterId(d.getCounterId());
        save.setItems(d.getItems().stream().map(i -> {
            CountSaveReq.Item item = new CountSaveReq.Item();
            item.setMaterialSkuId(i.getMaterialSkuId());
            item.setCountedQty(i.getMaterialSkuId() == f.skuA ? new BigDecimal("4") : null);
            return item;
        }).toList());
        countService.update(created.id(), save);
        byte[] sheet = countService.sheet(created.id());
        ImportResult result = countService.importSheet(created.id(), new MockMultipartFile("file", "盘点表.xlsx",
                Excel.CONTENT_TYPE, sheet));
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.notes()).extracting(ImportError::message).containsExactly("1 行实盘数是空的，跳过了");
        CountDetailVO imported = countService.detail(created.id());
        assertThat(imported.getVersion()).isEqualTo(d.getVersion() + 2);
        assertThat(imported.getLossCount()).isEqualTo(1);
    }

    // ---------- 工具 ----------

    private record Fixture(long company, long warehouseA, long warehouseB, long skuA, long skuB, long counter) {
    }

    private Fixture fixture() {
        String s = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        long company = jdbcTemplate.queryForObject("INSERT INTO bas_company (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "K" + s.substring(1), "IT 主体" + s, "IT 主体" + s);
        long warehouseA = jdbcTemplate.queryForObject("INSERT INTO bas_warehouse (code, name, type) VALUES (?, ?, 'MATERIAL') RETURNING id",
                Long.class, "ITA" + s, "IT 面料仓" + s);
        long warehouseB = jdbcTemplate.queryForObject("INSERT INTO bas_warehouse (code, name, type) VALUES (?, ?, 'MATERIAL') RETURNING id",
                Long.class, "ITB" + s, "IT 辅料仓" + s);
        long category = jdbcTemplate.queryForObject("INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id",
                Long.class, "IT 库存分类" + s);
        long material = jdbcTemplate.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit)
                VALUES (?, 'IT 库存面料', ?, 'FABRIC', 'METER', 'METER') RETURNING id""", Long.class, "ITM" + s, category);
        long color = TestColors.insert(jdbcTemplate, "IT 色" + s);
        long skuA = jdbcTemplate.queryForObject("INSERT INTO bas_material_sku (material_id, code, color_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, material, "ITM" + s + "-A", color);
        long skuB = jdbcTemplate.queryForObject("INSERT INTO bas_material_sku (material_id, code, spec) VALUES (?, ?, '150cm') RETURNING id",
                Long.class, material, "ITM" + s + "-B");
        long counter = jdbcTemplate.queryForObject("INSERT INTO sys_user (username, password, nickname) VALUES (?, 'x', 'IT 盘点人') RETURNING id",
                Long.class, "it-count-" + s);
        TestIdentity.company(company);
        return new Fixture(company, warehouseA, warehouseB, skuA, skuB, counter);
    }


    private static InvDocCreateReq createReq(Fixture f, InvDocType type, Long from, Long to, List<InvDocItemReq> items) {
        InvDocCreateReq req = new InvDocCreateReq();
        req.setDocType(type);
        req.setCompanyId(f.company);
        req.setDocDate(LocalDate.now());
        req.setFromWarehouseId(from);
        req.setToWarehouseId(to);
        req.setReason(type.getReasonDict() == null ? null : "OTHER");
        req.setItems(items);
        return req;
    }

    private long create(Fixture f, InvDocType type, Long from, Long to, long sku, String qty) {
        return invDocService.create(createReq(f, type, from, to, List.of(itemReq(sku, qty)))).id();
    }

    private static InvDocItemReq itemReq(long sku, String qty) {
        InvDocItemReq i = new InvDocItemReq();
        i.setMaterialSkuId(sku);
        i.setQty(new BigDecimal(qty));
        return i;
    }

    private static InvDocSaveReq saveReq(InvDocDetailVO d, List<InvDocItemReq> items) {
        InvDocSaveReq req = new InvDocSaveReq();
        req.setVersion(d.getVersion());
        req.setCompanyId(d.getCompanyId());
        req.setDocDate(d.getDocDate());
        req.setFromWarehouseId(d.getFromWarehouseId());
        req.setToWarehouseId(d.getToWarehouseId());
        req.setReason(d.getReason());
        req.setItems(items);
        return req;
    }

    private int version(long docId) {
        return invDocService.detail(docId).getVersion();
    }

    private void audit(long docId) {
        invDocService.act(docId, "audit", new DocActionReq(version(docId), null));
    }

    /** 并发里用：成功 "ok"，业务错误返回错误码 */
    private Object tryAudit(long docId) {
        try {
            audit(docId);
            return "ok";
        } catch (BizException e) {
            return e.getCode();
        }
    }

    /** 两件事同时开跑（各自登录），按给的顺序收结果 */
    @SafeVarargs
    private static List<Object> race(Callable<Object>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    loginAs();
                    start.await();
                    try {
                        return task.call();
                    } finally {
                        TestIdentity.clear();
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private BigDecimal stock(long warehouse, long sku) {
        return stockService.qty(warehouse, List.of(sku)).getFirst().qty();
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private CountCreateReq countReq(Fixture f, CountPurpose purpose, List<Long> skus) {
        CountCreateReq req = new CountCreateReq();
        req.setCompanyId(f.company);
        req.setWarehouseId(f.warehouseA);
        req.setPurpose(purpose);
        req.setScope(CountScope.PICKED);
        req.setSkuIds(skus);
        req.setCountDate(LocalDate.now());
        req.setCounterId(f.counter);
        return req;
    }

    /** 每行都填同一个实盘数，存草稿 */
    private void fill(long countId, Map<Long, String> counted) {
        CountDetailVO d = countService.detail(countId);
        CountSaveReq req = new CountSaveReq();
        req.setVersion(d.getVersion());
        req.setCountDate(d.getCountDate());
        req.setCounterId(d.getCounterId());
        req.setItems(d.getItems().stream().map(i -> {
            CountSaveReq.Item item = new CountSaveReq.Item();
            item.setMaterialSkuId(i.getMaterialSkuId());
            item.setCountedQty(new BigDecimal(counted.get(i.getMaterialSkuId())));
            return item;
        }).toList());
        countService.update(countId, req);
    }

    private static CountItemVO item(CountDetailVO detail, long skuId) {
        return detail.getItems().stream().filter(i -> i.getMaterialSkuId() == skuId).findFirst().orElseThrow();
    }

    private void fill(long countId, String counted) {
        CountDetailVO d = countService.detail(countId);
        CountSaveReq req = new CountSaveReq();
        req.setVersion(d.getVersion());
        req.setCountDate(d.getCountDate());
        req.setCounterId(d.getCounterId());
        req.setItems(d.getItems().stream().map(i -> {
            CountSaveReq.Item item = new CountSaveReq.Item();
            item.setMaterialSkuId(i.getMaterialSkuId());
            item.setCountedQty(new BigDecimal(counted));
            return item;
        }).toList());
        countService.update(countId, req);
    }

    private void submitAndAudit(long countId) {
        countService.act(countId, "submit", new DocActionReq(countService.detail(countId).getVersion(), null));
        CountDetailVO pending = countService.detail(countId);
        countService.act(countId, "audit", new DocActionReq(pending.getVersion(), null));
    }

}
