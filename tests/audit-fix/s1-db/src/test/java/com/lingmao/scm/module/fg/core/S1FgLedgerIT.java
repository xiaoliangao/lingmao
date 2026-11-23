package com.lingmao.scm.module.fg.core;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.exception.BizException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.module.fg.dto.FgDocQuery;
import com.lingmao.scm.module.fg.entity.FgDoc;
import com.lingmao.scm.module.fg.entity.FgDocItem;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.fg.entity.FgDocType;
import com.lingmao.scm.module.fg.mapper.FgDocMapper;
import com.lingmao.scm.module.fg.mapper.FgDocItemMapper;
import com.lingmao.scm.module.fg.mapper.FgStockLogMapper;
import com.lingmao.scm.module.fg.mapper.FgStockMapper;
import com.lingmao.scm.module.production.mapper.ProductionReceiptMapper;
import com.lingmao.scm.security.DataScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真 PostgreSQL、真 Mapper/领域过账与各自提交的事务；不将 Mockito 调用计数当并发证据。 */
@IntegrationTest
class S1FgLedgerIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired FgLedger ledger;
    @Autowired FgDocMapper documents;
    @Autowired FgDocItemMapper items;
    @Autowired FgStockMapper stocks;
    @Autowired FgStockLogMapper logs;
    @Autowired ProductionReceiptMapper receipts;
    @Autowired JsonMapper json;

    @AfterEach
    void clearIdentity() { TestIdentity.clear(); }

    @Test
    void fiftyRealContendersConsumeOnlyExistingCompanyStockAndKeepLosersAsDraft() throws Exception {
        Fixture f = fixture();
        post(f, document(f, FgDocType.OTHER_IN, Map.of(f.skuA(), 25)), List.of(new FgLedger.Move(f.warehouse(), f.skuA(), 25, 1)));
        List<Long> ids = IntStream.range(0, 50).mapToObj(i -> document(f, FgDocType.OTHER_OUT, Map.of(f.skuA(), 1))).toList();
        Set<Integer> backends = ConcurrentHashMap.newKeySet();
        List<Integer> results = race(50, n -> () -> outcome(() -> transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL lock_timeout='8s'");
            backends.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            approve(ids.get(n), 0);
            ledger.post(posting(f, ids.get(n), FgDocType.OTHER_OUT, List.of(new FgLedger.Move(f.warehouse(), f.skuA(), -1, 1))));
        })));
        assertThat(backends.size()).isGreaterThan(1);
        assertThat(results.stream().filter(code -> code == 0).count()).isEqualTo(25);
        assertThat(results.stream().filter(code -> code == 1001).count()).isEqualTo(25);
        assertThat(stock(f, f.skuA())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_doc WHERE company_id=? AND kind='OUT' AND status='DRAFT' AND version=0",
                Integer.class, f.company())).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_stock WHERE company_id=? AND qty<0", Integer.class, f.company())).isZero();
        artifact("fifty-contenders", Map.of("workers",50,"databaseBackends",backends.size(),"results",results,"endingQty",stock(f,f.skuA())));
    }

    @Test
    void oppositeInputSkuOrdersUseOneGlobalLockOrderAndNoDeadlock() throws Exception {
        Fixture f = fixture();
        post(f, document(f, FgDocType.OTHER_IN, Map.of(f.skuA(), 50, f.skuB(), 50)),
                List.of(new FgLedger.Move(f.warehouse(), f.skuB(), 50, 2), new FgLedger.Move(f.warehouse(), f.skuA(), 50, 1)));
        List<Long> ids = IntStream.range(0,50).mapToObj(i -> document(f,FgDocType.OTHER_OUT,Map.of(f.skuA(),1,f.skuB(),1))).toList();
        List<Integer> results = race(50, n -> () -> outcome(() -> {
            List<FgLedger.Move> moves = n % 2 == 0
                    ? List.of(new FgLedger.Move(f.warehouse(),f.skuA(),-1,1),new FgLedger.Move(f.warehouse(),f.skuB(),-1,2))
                    : List.of(new FgLedger.Move(f.warehouse(),f.skuB(),-1,2),new FgLedger.Move(f.warehouse(),f.skuA(),-1,1));
            post(f,ids.get(n),moves);
        }));
        assertThat(results).containsOnly(0).hasSize(50);
        assertThat(stock(f,f.skuA())).isZero();
        assertThat(stock(f,f.skuB())).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(movement_revision) FROM fg_stock WHERE company_id=?",Long.class,f.company())).isEqualTo(102);
        artifact("reverse-order-skus",Map.of("workers",50,"results",results,"finalQty",List.of(stock(f,f.skuA()),stock(f,f.skuB()))));
    }

    @Test
    void oneShortSkuRollsBackOtherSkuBalanceStatusVersionAndEvents() {
        Fixture f = fixture();
        post(f,document(f,FgDocType.OTHER_IN,Map.of(f.skuA(),3)),List.of(new FgLedger.Move(f.warehouse(),f.skuA(),3,1)));
        long out = document(f,FgDocType.OTHER_OUT,Map.of(f.skuA(),1,f.skuB(),1));
        assertThat(outcome(() -> post(f,out,List.of(new FgLedger.Move(f.warehouse(),f.skuA(),-1,1),
                new FgLedger.Move(f.warehouse(),f.skuB(),-1,2))))).isEqualTo(1001);
        assertThat(stock(f,f.skuA())).isEqualTo(3);
        assertThat(documents.selectById(out).getStatus()).isEqualTo(DocStatus.DRAFT);
        assertThat(documents.selectById(out).getVersion()).isZero();
        assertThat(logs.docPosted(out)).isFalse();
    }

    @Test
    void companyBalancesCannotBeBorrowedAndWrongLedgerCompanyRollsBack() {
        Fixture a = fixture();
        long otherCompany = company();
        Fixture b = new Fixture(otherCompany,a.warehouse(),a.style(),a.skuA(),a.skuB());
        post(a,document(a,FgDocType.OTHER_IN,Map.of(a.skuA(),7)),List.of(new FgLedger.Move(a.warehouse(),a.skuA(),7,1)));
        long out = document(b,FgDocType.OTHER_OUT,Map.of(a.skuA(),1));
        assertThat(outcome(() -> post(b,out,List.of(new FgLedger.Move(a.warehouse(),a.skuA(),-1,1))))).isEqualTo(1001);
        assertThat(stocks.selectBySkus(otherCompany,a.warehouse(),List.of(a.skuA()))).isEmpty();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            approve(out,0);
            ledger.post(posting(a,out,FgDocType.OTHER_OUT,List.of(new FgLedger.Move(a.warehouse(),a.skuA(),-1,1))));
        })).isInstanceOf(DataAccessException.class);
        assertThat(stock(a,a.skuA())).isEqualTo(7);
        assertThat(documents.selectById(out).getVersion()).isZero();
    }

    @Test
    void reversalUsesOriginalEventAndReauditCreatesANewReversibleCycle() throws Exception {
        Fixture f = fixture();
        long doc = document(f,FgDocType.OTHER_IN,Map.of(f.skuA(),2));
        post(f,doc,List.of(new FgLedger.Move(f.warehouse(),f.skuA(),2,1)));
        long original = logs.reversibleOf(f.company(),doc).getFirst().getId();
        transactions.executeWithoutResult(status -> {
            reverseStatus(doc,1);
            // 模拟调用者在反审事务内已改过草稿，核心仍必须用原事件而非当前可编辑行。
            jdbc.update("UPDATE fg_doc_item SET qty=99 WHERE doc_id=?",doc);
            ledger.reverse(f.company(),doc);
        });
        assertThat(stock(f,f.skuA())).isZero();
        assertThat(jdbc.queryForObject("SELECT change_qty FROM fg_stock_log WHERE reversal_of_log_id=?",Integer.class,original)).isEqualTo(-2);
        assertThat(logs.reversibleOf(f.company(),doc)).isEmpty();
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE fg_doc_item SET qty=3 WHERE doc_id=?",doc);
            approve(doc,2);
            ledger.post(posting(f,doc,FgDocType.OTHER_IN,List.of(new FgLedger.Move(f.warehouse(),f.skuA(),3,1))));
        });
        long second = logs.reversibleOf(f.company(),doc).getFirst().getId();
        assertThat(second).isNotEqualTo(original);
        transactions.executeWithoutResult(status -> { reverseStatus(doc,3); ledger.reverse(f.company(),doc); });
        assertThat(stock(f,f.skuA())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_stock_log WHERE fg_doc_id=?",Integer.class,doc)).isEqualTo(4);
        assertThat(outcome(() -> transactions.executeWithoutResult(status -> ledger.reverse(f.company(),doc)))).isEqualTo(1002);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_doc_item WHERE doc_id=? AND price IS NULL AND amount IS NULL",Integer.class,doc)).isEqualTo(1);
        artifact("reversal-cycles",jdbc.queryForList("SELECT id,company_id,change_qty,balance_qty,reversal_of_log_id FROM fg_stock_log WHERE fg_doc_id=? ORDER BY id",doc));
    }

    @Test
    void consumedInboundDocumentCannotBeReversedAndImmutableLedgerRejectsEveryMutation() {
        Fixture f = fixture();
        long in = document(f,FgDocType.OTHER_IN,Map.of(f.skuA(),2));
        post(f,in,List.of(new FgLedger.Move(f.warehouse(),f.skuA(),2,1)));
        post(f,document(f,FgDocType.OTHER_OUT,Map.of(f.skuA(),1)),List.of(new FgLedger.Move(f.warehouse(),f.skuA(),-1,1)));
        assertThat(outcome(() -> transactions.executeWithoutResult(status -> { reverseStatus(in,1); ledger.reverse(f.company(),in); }))).isEqualTo(1001);
        assertThat(documents.selectById(in).getStatus()).isEqualTo(DocStatus.APPROVED);
        assertThat(documents.selectById(in).getVersion()).isEqualTo(1);
        long event = logs.reversibleOf(f.company(),in).getFirst().getId();
        String before = jdbc.queryForObject("SELECT row_to_json(l)::text FROM fg_stock_log l WHERE id=?",String.class,event);
        blocked("UPDATE fg_stock_log SET change_qty=8 WHERE id=?",event);
        blocked("DELETE FROM fg_stock_log WHERE id=?",event);
        blocked("TRUNCATE fg_stock_log");
        assertThat(jdbc.queryForObject("SELECT row_to_json(l)::text FROM fg_stock_log l WHERE id=?",String.class,event)).isEqualTo(before);
    }

    @Test
    void negativeInputAndSecondVersionWriterAreRejectedByRealCoreAndCas() throws Exception {
        Fixture f = fixture();
        long doc = document(f,FgDocType.OTHER_IN,Map.of(f.skuA(),1));
        assertThat(outcome(() -> post(f,doc,List.of(new FgLedger.Move(f.warehouse(),f.skuA(),-1,1))))).isEqualTo(400);
        assertThat(documents.selectById(doc).getVersion()).isZero();
        List<Integer> codes = race(2,n -> () -> outcome(() -> post(f,doc,List.of(new FgLedger.Move(f.warehouse(),f.skuA(),1,1)))));
        assertThat(codes).containsExactlyInAnyOrder(0,1002);
        assertThat(stock(f,f.skuA())).isEqualTo(1);
        assertThat(logs.reversibleOf(f.company(),doc)).hasSize(1);
    }

    @Test
    void sourceCompanyIsARealForeignKeyAndPostedItemsRejectSqlChanges() {
        Fixture f = fixture();
        long user = jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES (?,'unusable','S1 FG') RETURNING id",Long.class,token());
        long order = jdbc.queryForObject("INSERT INTO ord_order(no,company_id,order_type,order_date,required_date,sales_id,merch_id) VALUES (?,?,'BULK',current_date,current_date,?,?) RETURNING id",
                Long.class,token(),f.company(),user,user);
        long other = company();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO fg_doc(no,company_id,kind,doc_type,from_warehouse_id,order_id,doc_date) VALUES (?,?,'OUT','SHIP_OUT',?,?,current_date)",
                token(),other,f.warehouse(),order)).isInstanceOf(DataAccessException.class);
        long doc = document(f,FgDocType.OTHER_IN,Map.of(f.skuA(),1));
        post(f,doc,List.of(new FgLedger.Move(f.warehouse(),f.skuA(),1,1)));
        assertThatThrownBy(() -> jdbc.update("UPDATE fg_doc_item SET qty=2 WHERE doc_id=?",doc)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM fg_doc_item WHERE doc_id=?",doc)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void anApprovedItemCannotBeReparentedIntoADraftDocument() {
        Fixture f=fixture();long posted=document(f,FgDocType.OTHER_IN,Map.of(f.skuA(),2));
        post(f,posted,List.of(new FgLedger.Move(f.warehouse(),f.skuA(),2,1)));
        long draft=document(f,FgDocType.OTHER_IN,Map.of(f.skuB(),1));
        assertThatThrownBy(() -> jdbc.update("UPDATE fg_doc_item SET doc_id=? WHERE doc_id=?",draft,posted))
                .isInstanceOfSatisfying(DataAccessException.class,error -> assertThat(error.getMostSpecificCause().getMessage()).contains("FG_ITEM_REPARENT_FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_doc_item WHERE doc_id=?",Integer.class,posted)).isEqualTo(1);
    }

    @Test
    void aRealConcurrentDirectItemWriterCannotPassTheAuditHeaderLock() throws Exception {
        Fixture f=fixture();long doc=document(f,FgDocType.OTHER_IN,Map.of(f.skuA(),1));
        CountDownLatch locked=new CountDownLatch(1),attempted=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var auditor=pool.submit(() -> transactions.executeWithoutResult(status -> {
                documents.lockById(doc);locked.countDown();
                try { if(!attempted.await(10,TimeUnit.SECONDS)) throw new AssertionError("writer did not attempt"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new AssertionError(failure); }
                approve(doc,0);ledger.post(posting(f,doc,FgDocType.OTHER_IN,List.of(new FgLedger.Move(f.warehouse(),f.skuA(),1,1))));
            }));
            var writer=pool.submit(() -> {
                if(!locked.await(10,TimeUnit.SECONDS)) throw new AssertionError("auditor did not lock");
                try {
                    assertThatThrownBy(() -> jdbc.update("UPDATE fg_doc_item SET qty=2 WHERE doc_id=?",doc))
                            .isInstanceOfSatisfying(DataAccessException.class,error -> assertThat(error.getMostSpecificCause().getMessage()).contains("FG_DOCUMENT_IS_BEING_CHANGED_RETRY"));
                } finally { attempted.countDown(); }
                return true;
            });
            assertThat(writer.get(15,TimeUnit.SECONDS)).isTrue();auditor.get(15,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT qty FROM fg_doc_item WHERE doc_id=?",Integer.class,doc)).isEqualTo(1);
        assertThat(stock(f,f.skuA())).isEqualTo(1);
    }

    @Test
    void rawSqlCannotAddWrongSkuWrongQuantityOrDuplicatePostingVersion() {
        Fixture f=fixture();long doc=document(f,FgDocType.OTHER_IN,Map.of(f.skuA(),2));
        post(f,doc,List.of(new FgLedger.Move(f.warehouse(),f.skuA(),2,1)));
        long original=logs.reversibleOf(f.company(),doc).getFirst().getId();
        String copy="INSERT INTO fg_stock_log(fg_doc_id,company_id,change_type,doc_no,biz_date,warehouse_id,style_sku_id,change_qty,balance_qty) "
                +"SELECT fg_doc_id,company_id,change_type,doc_no,biz_date,warehouse_id,?, ?,balance_qty FROM fg_stock_log WHERE id=?";
        assertThatThrownBy(() -> jdbc.update(copy,f.skuB(),2,original)).isInstanceOfSatisfying(DataAccessException.class,
                error -> assertThat(error.getMostSpecificCause().getMessage()).contains("FG_LEDGER_ITEM_MISMATCH"));
        assertThatThrownBy(() -> jdbc.update(copy,f.skuA(),3,original)).isInstanceOfSatisfying(DataAccessException.class,
                error -> assertThat(error.getMostSpecificCause().getMessage()).contains("FG_LEDGER_ITEM_MISMATCH"));
        assertThatThrownBy(() -> jdbc.update(copy,f.skuA(),2,original)).isInstanceOfSatisfying(DataAccessException.class,
                error -> assertThat(error.getMostSpecificCause().getMessage()).contains("uk_fg_log_posting_version"));
        assertThat(jdbc.queryForObject("SELECT posting_version FROM fg_stock_log WHERE id=?",Integer.class,original)).isEqualTo(1);
        transactions.executeWithoutResult(status -> { reverseStatus(doc,1);ledger.reverse(f.company(),doc); });
        String duplicateReverse="INSERT INTO fg_stock_log(fg_doc_id,company_id,change_type,doc_no,biz_date,warehouse_id,style_sku_id,change_qty,balance_qty,reversal_of_log_id) "
                +"SELECT fg_doc_id,company_id,change_type,doc_no,biz_date,warehouse_id,style_sku_id,change_qty,balance_qty,reversal_of_log_id FROM fg_stock_log WHERE reversal_of_log_id=?";
        assertThatThrownBy(() -> jdbc.update(duplicateReverse,original)).isInstanceOfSatisfying(DataAccessException.class,
                error -> assertThat(error.getMostSpecificCause().getMessage()).contains("uk_fg_log_reversal"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_stock_log WHERE fg_doc_id=?",Integer.class,doc)).isEqualTo(2);
    }

    @Test
    void reservedLocationCannotCreateAnotherPostingButWarehousesRemainIndependent() {
        Fixture a=fixture();long doc=document(a,FgDocType.OTHER_IN,Map.of(a.skuA(),2));
        post(a,doc,List.of(new FgLedger.Move(a.warehouse(),a.skuA(),2,1)));
        long secondWarehouse=jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES (?,'S1 FG second','PRODUCT') RETURNING id",Long.class,token());
        Fixture b=new Fixture(a.company(),secondWarehouse,a.style(),a.skuA(),a.skuB());
        post(b,document(b,FgDocType.OTHER_IN,Map.of(b.skuA(),3)),List.of(new FgLedger.Move(b.warehouse(),b.skuA(),3,1)));
        assertThat(stock(a,a.skuA())).isEqualTo(2);
        assertThat(stock(b,b.skuA())).isEqualTo(3);
        assertThat(outcome(() -> new FgLedger.Move(a.warehouse(),a.skuA(),1,2,1))).isEqualTo(400);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO fg_stock(company_id,warehouse_id,style_sku_id,location_id,qty) VALUES (?,?,?,1,2)",
                a.company(),a.warehouse(),a.skuA())).isInstanceOfSatisfying(DataAccessException.class,
                error -> assertThat(error.getMostSpecificCause().getMessage()).contains("ck_fg_stock_location"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO fg_stock_log(fg_doc_id,company_id,change_type,doc_no,biz_date,warehouse_id,style_sku_id,location_id,change_qty,balance_qty) "
                +"SELECT fg_doc_id,company_id,change_type,doc_no,biz_date,warehouse_id,style_sku_id,1,change_qty,balance_qty FROM fg_stock_log WHERE fg_doc_id=?",doc))
                .isInstanceOfSatisfying(DataAccessException.class,error -> assertThat(error.getMostSpecificCause().getMessage()).contains("FG_UNSUPPORTED_LOCATION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_stock_log WHERE fg_doc_id=?",Integer.class,doc)).isEqualTo(1);
    }

    @Test
    void sourceQueriesKeepAllOccupancyButLimitPreviewsToTwentyVisibleDocuments() throws Exception {
        Fixture f=fixture();
        long owner=jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES (?,'unusable','S1 source owner') RETURNING id",Long.class,token());
        long hiddenOwner=jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES (?,'unusable','S1 other owner') RETURNING id",Long.class,token());
        long order=jdbc.queryForObject("INSERT INTO ord_order(no,company_id,order_type,order_date,required_date,sales_id,merch_id) VALUES (?,?,'BULK',current_date,current_date,?,?) RETURNING id",Long.class,token(),f.company(),owner,owner);
        long orderItem=jdbc.queryForObject("INSERT INTO ord_order_item(order_id,seq,style_id,style_sku_id,qty) VALUES (?,1,?,?,400) RETURNING id",Long.class,order,f.style(),f.skuA());
        long factory=jdbc.queryForObject("INSERT INTO sup_supplier(code,name,short_name) VALUES (?,'S1 source factory','S1 factory') RETURNING id",Long.class,token());
        long pack=jdbc.queryForObject("INSERT INTO tp_tech_pack(style_id,stage,version) VALUES (?,'PROD',1) RETURNING id",Long.class,f.style());
        long production=jdbc.queryForObject("INSERT INTO prd_order(no,company_id,kind,factory_id,process_mode,order_id,style_id,tech_pack_id,order_date,due_date,unit_price,tax_pct,merch_id,settle_method,create_by) "
                +"VALUES (?,?,'BUY',?,'FULL',?,?,?,current_date,current_date,1,0,?,'MONTHLY',?) RETURNING id",Long.class,token(),f.company(),factory,order,f.style(),pack,owner,owner);
        long productionItem=jdbc.queryForObject("INSERT INTO prd_order_item(prd_order_id,seq,order_item_id,style_sku_id,qty) VALUES (?,1,?,?,400) RETURNING id",Long.class,production,orderItem,f.skuA());
        long receipt=jdbc.queryForObject("INSERT INTO prd_receipt(no,company_id,prd_order_id,receipt_date,warehouse_id,status) VALUES (?,?,?,current_date,?,'APPROVED') RETURNING id",Long.class,token(),f.company(),production,f.warehouse());
        long receiptItem=jdbc.queryForObject("INSERT INTO prd_receipt_item(receipt_id,seq,prd_order_item_id,style_sku_id,qty,defect_qty) VALUES (?,1,?,?,400,4) RETURNING id",Long.class,receipt,productionItem,f.skuA());
        List<Long> ids=new ArrayList<>();
        for(int i=0;i<25;i++) {
            long id=jdbc.queryForObject("INSERT INTO fg_doc(no,company_id,kind,doc_type,to_warehouse_id,prd_receipt_id,doc_date,create_by,create_time) "
                    +"VALUES (?,?,'IN','RECEIPT_IN',?,?,current_date,?,'2026-11-22T00:00:00Z') RETURNING id",Long.class,token(),f.company(),f.warehouse(),receipt,i<21?owner:hiddenOwner);
            jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty,prd_receipt_item_id) VALUES (?,1,?,1,?)",id,f.skuA(),receiptItem);
            ids.add(id);
        }
        transactions.executeWithoutResult(status -> {
            receipts.lockById(receipt);
            post(f,ids.getFirst(),List.of(new FgLedger.Move(f.warehouse(),f.skuA(),1,1)));
        });
        jdbc.update("UPDATE fg_doc SET status='VOIDED' WHERE id=?",ids.getLast());
        TestIdentity.login(owner,List.of("fg:doc:list"));
        DataScope scope=DataScope.current(false);
        assertThat(receipts.selectStockSources(List.of(receipt),scope,f.company()))
                .containsExactly(new ProductionReceiptMapper.StockSource(receipt,f.company(),396));
        assertThat(receipts.selectStockSources(List.of(),scope,f.company())).isEmpty();
        assertThat(documents.receiptHead(receipt).companyId()).isEqualTo(f.company());
        assertThat(documents.receiptHead(receipt).prdOrderId()).isEqualTo(production);
        assertThat(documents.receiptHead(receipt).prdOrderNo()).isEqualTo(jdbc.queryForObject("SELECT no FROM prd_order WHERE id=?",String.class,production));
        assertThat(documents.orderHead(order).companyId()).isEqualTo(f.company());
        assertThat(documents.selectReceiptOccupancy(List.of(receipt),f.company()))
                .containsExactly(new FgDocMapper.ReceiptOccupancy(receipt,23,1));
        assertThat(documents.selectReceiptLineOccupancy(receipt,f.company()))
                .containsExactly(new FgDocMapper.ReceiptLineOccupancy(receiptItem,23,1));
        assertThat(documents.stockedByReceipt(receipt,null,f.company())).containsExactly(new FgDocMapper.LineQty(receiptItem,24));
        assertThat(documents.stockedByReceipt(receipt,ids.getFirst(),f.company())).containsExactly(new FgDocMapper.LineQty(receiptItem,23));
        assertThat(documents.stockedByReceiptLines(receipt,null,f.company(),List.of(receiptItem)))
                .containsExactly(new FgDocMapper.LineQty(receiptItem,24));
        assertThat(documents.stockedByReceiptLines(receipt,null,f.company(),List.of())).isEmpty();
        assertThat(documents.receiptLines(receipt,List.of(receiptItem),f.company()))
                .containsExactly(new FgDocMapper.ReceiptLine(receiptItem,f.skuA(),400,4));
        assertThat(documents.receiptLines(receipt,List.of(),f.company())).isEmpty();
        assertThat(documents.orderLines(order,List.of(orderItem),f.company()))
                .containsExactly(new FgDocMapper.OrderLine(orderItem,f.skuA(),400));
        assertThat(documents.orderLines(order,List.of(),f.company())).isEmpty();
        assertThat(documents.selectReceiptDocumentCounts(List.of(receipt),scope,f.company()))
                .containsExactly(new FgDocMapper.ReceiptDocumentCount(receipt,21));
        List<FgDocMapper.ReceiptDocument> preview=documents.selectReceiptDocumentPreviews(List.of(receipt),scope,f.company());
        assertThat(preview).hasSize(20);
        assertThat(preview.stream().map(FgDocMapper.ReceiptDocument::docId).toList())
                .containsExactlyElementsOf(ids.subList(1,21).reversed());
        assertThat(preview).allSatisfy(row -> assertThat(row.qty()).isEqualTo(1));
        long anotherCompany=company();
        assertThat(documents.selectReceiptOccupancy(List.of(receipt),anotherCompany)).isEmpty();
        assertThat(receipts.selectStockSources(List.of(receipt),scope,anotherCompany)).isEmpty();
        assertThat(documents.receiptLines(receipt,List.of(receiptItem),anotherCompany)).isEmpty();
        assertThat(documents.orderLines(order,List.of(orderItem),anotherCompany)).isEmpty();
        long voidedReceipt=jdbc.queryForObject("INSERT INTO prd_receipt(no,company_id,prd_order_id,receipt_date,warehouse_id,status) VALUES (?,?,?,current_date,?,'VOIDED') RETURNING id",Long.class,token(),f.company(),production,f.warehouse());
        assertThat(receipts.selectStockSources(List.of(receipt,voidedReceipt),scope,f.company()))
                .containsExactlyInAnyOrder(new ProductionReceiptMapper.StockSource(receipt,f.company(),396),
                        new ProductionReceiptMapper.StockSource(voidedReceipt,f.company(),0));
        jdbc.update("UPDATE ord_order SET status='APPROVED' WHERE id=?",order);
        long shipped=jdbc.queryForObject("INSERT INTO fg_doc(no,company_id,kind,doc_type,from_warehouse_id,order_id,doc_date) VALUES (?,?,'OUT','SHIP_OUT',?,?,current_date) RETURNING id",Long.class,token(),f.company(),f.warehouse(),order);
        jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty,order_item_id) VALUES (?,1,?,1,?)",shipped,f.skuA(),orderItem);
        post(f,shipped,List.of(new FgLedger.Move(f.warehouse(),f.skuA(),-1,1)));
        assertThat(documents.shippedByOrder(order,null,f.company())).containsExactly(new FgDocMapper.LineQty(orderItem,1));
        assertThat(documents.shippedBySku(order,f.company())).containsExactly(new FgDocMapper.LineQty(f.skuA(),1));
        assertThat(documents.shippedByOrderLines(order,null,f.company(),List.of(orderItem)))
                .containsExactly(new FgDocMapper.LineQty(orderItem,1));
        assertThat(documents.shippedByOrderLines(order,null,f.company(),List.of())).isEmpty();
        TestIdentity.login(hiddenOwner,List.of("fg:doc:list"));
        assertThat(receipts.selectStockSources(List.of(receipt),DataScope.current(false),f.company())).isEmpty();
        artifact("receipt-visible-preview",Map.of("receiptId",receipt,"sourceGoodQty",396,"reservedQty",23,"postedQty",1,
                "visibleDocumentsTotal",21,"preview",preview,"hiddenSourceRejected",true));
    }

    @Test
    void batchItemUpdatePreservesIdsClearsNullsAndCannotMatchAnotherDocumentOrSku() throws Exception {
        Fixture f=fixture();
        long user=jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES (?,'unusable','S1 update') RETURNING id",Long.class,token());
        long order=jdbc.queryForObject("INSERT INTO ord_order(no,company_id,order_type,order_date,required_date,sales_id,merch_id) VALUES (?,?,'BULK',current_date,current_date,?,?) RETURNING id",Long.class,token(),f.company(),user,user);
        long first=jdbc.queryForObject("INSERT INTO ord_order_item(order_id,seq,style_id,style_sku_id,qty) VALUES (?,1,?,?,10) RETURNING id",Long.class,order,f.style(),f.skuA());
        long second=jdbc.queryForObject("INSERT INTO ord_order_item(order_id,seq,style_id,style_sku_id,qty) VALUES (?,2,?,?,10) RETURNING id",Long.class,order,f.style(),f.skuB());
        long doc=jdbc.queryForObject("INSERT INTO fg_doc(no,company_id,kind,doc_type,from_warehouse_id,order_id,doc_date) VALUES (?,?,'OUT','SHIP_OUT',?,?,current_date) RETURNING id",Long.class,token(),f.company(),f.warehouse(),order);
        jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty,order_item_id,price,amount,remark) VALUES (?,1,?,1,?,9.99,9.99,'old'),(?,2,?,1,?,9.99,9.99,'old')",doc,f.skuA(),first,doc,f.skuB(),second);
        TestIdentity.login(user,List.of("fg:doc:list","data:all"));
        FgDocQuery query=new FgDocQuery();query.setCompanyId(f.company());query.setKind(FgDocKind.OUT);query.setIds(List.of(doc));
        assertThat(documents.selectRowPage(new Page<>(1,20),query,null,DataScope.current(false),f.company())
                .getRecords().getFirst().getTotalAmount()).isEqualByComparingTo("19.98");
        List<FgDocItem> before=items.selectByDoc(doc);List<Long> originalIds=before.stream().map(FgDocItem::getId).toList();
        FgDocItem a=before.get(0),b=before.get(1);
        a.setQty(2);a.setPrice(null);a.setAmount(null);a.setRemark(null);
        b.setQty(3);b.setPrice(new BigDecimal("2.50"));b.setAmount(new BigDecimal("7.50"));b.setRemark("updated");
        transactions.executeWithoutResult(status -> {
            documents.lockById(doc);
            assertThat(items.updateExisting(doc,List.of(a,b))).isEqualTo(2);
        });
        List<FgDocItem> after=items.selectByDoc(doc);
        assertThat(after.stream().map(FgDocItem::getId).toList()).isEqualTo(originalIds);
        assertThat(after.getFirst().getPrice()).isNull();assertThat(after.getFirst().getAmount()).isNull();assertThat(after.getFirst().getRemark()).isNull();
        assertThat(after.getLast().getPrice()).isEqualByComparingTo("2.50");
        assertThat(after.getLast().getAmount()).isEqualByComparingTo("7.50");
        assertThat(after.stream().map(FgDocItem::getQty).toList()).containsExactly(2,3);
        var row=documents.selectRowPage(new Page<>(1,20),query,null,DataScope.current(false),f.company()).getRecords().getFirst();
        assertThat(row.getTotalAmount()).isNull();
        assertThat(row.getTotalQty().longValue()).isEqualTo(5L);
        long other=document(f,FgDocType.OTHER_IN,Map.of(f.skuA(),1));
        FgDocItem foreign=items.selectByDoc(other).getFirst();foreign.setQty(99);
        assertThat(items.updateExisting(doc,List.of(foreign))).isZero();
        assertThat(items.selectByDoc(other).getFirst().getQty()).isEqualTo(1);
        a.setStyleSkuId(f.skuB());a.setQty(99);
        assertThat(items.updateExisting(doc,List.of(a))).isZero();
        assertThat(items.updateExisting(doc,List.of())).isZero();
        assertThat(items.selectByDoc(doc).getFirst().getQty()).isEqualTo(2);
        artifact("batch-item-update",Map.of("docId",doc,"originalIds",originalIds,"rows",after,"crossDocumentUpdated",0,"wrongSkuUpdated",0));
    }

    private void approve(long doc,int version) {
        StatusCas.update(documents,new FgDoc(),StatusCas.of(FgDoc::getId,doc,FgDoc::getStatus,DocStatus.DRAFT,DocStatus.APPROVED)
                .eq(FgDoc::getVersion,version).setSql("version=version+1"));
    }
    private void reverseStatus(long doc,int version) {
        StatusCas.update(documents,new FgDoc(),StatusCas.of(FgDoc::getId,doc,FgDoc::getStatus,DocStatus.APPROVED,DocStatus.DRAFT)
                .eq(FgDoc::getVersion,version).setSql("version=version+1"));
    }
    private void post(Fixture f,long doc,List<FgLedger.Move> moves) {
        transactions.executeWithoutResult(status -> {
            FgDoc head = documents.selectById(doc);
            approve(doc,0);
            ledger.post(posting(f,doc,head.getDocType(),moves));
        });
    }
    private FgLedger.Posting posting(Fixture f,long doc,FgDocType type,List<FgLedger.Move> moves) {
        FgDoc head=documents.selectById(doc);
        return new FgLedger.Posting(f.company(),doc,head.getNo(),type,head.getDocDate(),moves);
    }
    private int stock(Fixture f,long sku) { Integer value=stocks.current(f.company(),f.warehouse(),sku,0); return value==null?0:value; }
    private int outcome(Runnable work) { try { work.run(); return 0; } catch (BizException failure) { return failure.getCode(); } }
    private <T> List<T> race(int workers,IntFunction<Callable<T>> task) throws Exception {
        CountDownLatch ready=new CountDownLatch(workers),go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(workers)) {
            List<java.util.concurrent.Future<T>> futures=new ArrayList<>();
            for(int n=0;n<workers;n++) { int index=n; futures.add(pool.submit(() -> { ready.countDown(); if(!go.await(15,TimeUnit.SECONDS)) throw new AssertionError("race start timeout"); return task.apply(index).call(); })); }
            assertThat(ready.await(15,TimeUnit.SECONDS)).isTrue(); go.countDown();
            List<T> result=new ArrayList<>(); for(var future:futures) result.add(future.get(45,TimeUnit.SECONDS)); return result;
        }
    }
    private void blocked(String sql,Object... args) {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> { status.setRollbackOnly(); jdbc.update(sql,args); }))
                .isInstanceOfSatisfying(DataAccessException.class,error -> assertThat(error.getMostSpecificCause())
                        .isInstanceOfSatisfying(SQLException.class,cause -> assertThat(cause.getSQLState()).isEqualTo("55000")));
    }
    private void artifact(String name,Object value) throws Exception {
        Path file=Path.of("target","a1-fg",name+".json");Files.createDirectories(file.getParent());Files.writeString(file,json.writeValueAsString(value));
    }
    private String token() { return "FG"+UUID.randomUUID().toString().replace("-","").substring(0,20); }
    private long company() {
        long id=jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('bas_company','id'))",Long.class);
        String code="G"+Long.toString(id,36).toUpperCase(java.util.Locale.ROOT);
        return jdbc.queryForObject("INSERT INTO bas_company(id,code,name,short_name) OVERRIDING SYSTEM VALUE VALUES (?,?,?,'S1 FG') RETURNING id",Long.class,id,code,token());
    }
    private Fixture fixture() {
        long company=company();String code=token();
        long warehouse=jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES (?,?,'PRODUCT') RETURNING id",Long.class,code,code);
        long category=jdbc.queryForObject("INSERT INTO bas_category(name,level) VALUES (?,1) RETURNING id",Long.class,code);
        long group=jdbc.queryForObject("INSERT INTO bas_size_group(name,base_size) VALUES (?,'M') RETURNING id",Long.class,code);
        jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES (?,1,'M'),(?,2,'L')",group,group);
        Long color=jdbc.queryForObject("INSERT INTO bas_color(code,name) VALUES ('SFG','S1 FG common color') ON CONFLICT(code) DO UPDATE SET code=EXCLUDED.code RETURNING id",Long.class);
        long style=jdbc.queryForObject("INSERT INTO sty_style(code,name,style_class,brand,category_id,year,season,source,size_group_id,status) VALUES (?,'S1 FG','FS','S1',?,2026,'AUTUMN','OWN',?,'ENABLED') RETURNING id",Long.class,code,category,group);
        jdbc.update("INSERT INTO sty_style_color(style_id,color_id,seq) VALUES (?,?,1)",style,color);
        long a=jdbc.queryForObject("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) VALUES (?,?,?,'M') RETURNING id",Long.class,style,code+"M",color);
        long b=jdbc.queryForObject("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) VALUES (?,?,?,'L') RETURNING id",Long.class,style,code+"L",color);
        return new Fixture(company,warehouse,style,a,b);
    }
    private long document(Fixture f,FgDocType type,Map<Long,Integer> quantities) {
        long id=jdbc.queryForObject("INSERT INTO fg_doc(no,company_id,kind,doc_type,from_warehouse_id,to_warehouse_id,doc_date,reason) VALUES (?,?,?,?,?,?,current_date,'OTHER') RETURNING id",
                Long.class,token(),f.company(),type.getKind().name(),type.name(),type.getKind()==FgDocKind.OUT?f.warehouse():null,type.getKind()==FgDocKind.IN?f.warehouse():null);
        int seq=0;for(var line:quantities.entrySet()) jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty) VALUES (?,?,?,?)",id,++seq,line.getKey(),line.getValue());
        return id;
    }
    private record Fixture(long company,long warehouse,long style,long skuA,long skuB) { }
}
