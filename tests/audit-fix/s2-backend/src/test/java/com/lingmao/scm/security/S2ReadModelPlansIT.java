package com.lingmao.scm.security;

import com.lingmao.scm.SqlStatementCapture;
import com.lingmao.scm.module.board.dto.UpstreamQuery;
import com.lingmao.scm.module.board.service.HomeService;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.service.OrderService;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 在真实查询拦截点捕获 SQL/参数，用 PostgreSQL EXPLAIN ANALYZE 验证新读模型索引。 */
class S2ReadModelPlansIT extends A2BusinessFixture {
    @Autowired HomeService home;
    @Autowired OrderService orders;
    @Autowired SqlSessionFactory sessions;

    @Test void pendingNoticePreviewAndSubmittedQueueUseTheirPartialIndexes() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "SELF", "purchase:order:list", "order:order:list");
        long source = sourceOrder(f, actor, "PENDING", f.company()), purchase = sourcePurchase(f, actor);
        jdbc.update("INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,user_id,status) VALUES('ORDER',?,1,1,?,'CURRENT')", source, actor.id());
        jdbc.update("INSERT INTO biz_doc_log(doc_type,doc_id,action,create_time) VALUES('ORDER',?,'submit',now())", source);
        jdbc.update("""
                WITH generated AS (
                    INSERT INTO ord_order(no,company_id,order_type,order_date,required_date,sales_id,merch_id,status,total_qty,create_by)
                    SELECT ? || n,?,'STOCK',CURRENT_DATE,CURRENT_DATE+30,?,?,'PENDING',0,? FROM generate_series(1,1024) n
                    RETURNING id
                ) INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,user_id,status)
                  SELECT 'ORDER',id,1,1,?,'CURRENT' FROM generated
                """, "S2PLAN" + suffix(), f.company(), actor.id(), actor.id(), actor.id(), actor.id());
        jdbc.update("""
                INSERT INTO biz_doc_log(doc_type,doc_id,action,create_time)
                SELECT 'ORDER',8000000000+n,'submit',now()-interval '1 day' FROM generate_series(1,20000) n
                """);
        jdbc.update("""
                INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,owner_id,create_time,handled_time)
                SELECT 'PURCHASE_ORDER',?,'ORDER',?,'S2 historical notification',?,now()-interval '1 day',now()
                FROM generate_series(1,20000)
                """, purchase, source, actor.id());
        jdbc.update("""
                INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,owner_id)
                SELECT 'PURCHASE_ORDER',?,'ORDER',?,'S2 current notification',? FROM generate_series(1,125)
                """, purchase, source, actor.id());
        // 5,000 real visible document groups, 100,000 unhandled notices. This deliberately
        // exposes the aggregate-before-seek cost instead of claiming index-only O(page) work.
        jdbc.update("""
                WITH generated AS (
                    INSERT INTO pur_order(no,company_id,supplier_id,purchase_type,buyer_id,order_date,delivery_date,warehouse_id,settle_method,tax_pct,status,total_qty,total_amount,create_by)
                    SELECT ? || n,?,?,'STOCK',?,CURRENT_DATE,CURRENT_DATE+30,?,'MONTHLY',13,'APPROVED',0,0,?
                    FROM generate_series(1,5000) n RETURNING id
                ) INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,owner_id,create_time)
                  SELECT 'PURCHASE_ORDER',g.id,'ORDER',?,'S2 pending group scale',?,now()-interval '2 day'
                  FROM generated g CROSS JOIN generate_series(1,20)
                """, "S2PPLAN" + suffix(), f.company(), f.supplier(), actor.id(), f.warehouse(), actor.id(), source, actor.id());
        jdbc.execute("ANALYZE biz_doc_notice"); jdbc.execute("ANALYZE biz_doc_log");
        jdbc.execute("ANALYZE pur_order"); jdbc.execute("ANALYZE ord_order"); jdbc.execute("ANALYZE biz_approval_step");
        authenticate(actor);
        UpstreamQuery upstream = new UpstreamQuery(); upstream.setSize(1);
        var notices = SqlStatementCapture.capture(() -> home.upstream(upstream));
        assertThat(notices.result().rows()).hasSize(1);
        assertThat(notices.result().total()).isEqualTo(5001);
        assertThat(notices.result().rows().getFirst().notices()).hasSize(100);
        explain("notice", notices.statements().stream().filter(sql -> sql.getSql().contains("biz_doc_notice")).toList(),
                "idx_notice_pending_doc_time", "JOIN LATERAL");
        OrderQuery q = new OrderQuery(); q.setAuditForMe(true); q.setOldestFirst(true); q.setSize(20);
        var audit = SqlStatementCapture.capture(() -> orders.page(q));
        assertThat(audit.result().getTotal()).isEqualTo(1025);
        explain("audit", audit.statements().stream().filter(sql -> sql.getSql().contains("FROM ord_order o") && sql.getSql().contains("biz_doc_log")).toList(),
                "idx_doc_log_latest_submit", "LATERAL");
    }

    private void explain(String name, List<org.apache.ibatis.mapping.BoundSql> sqls, String index, String required) throws Exception {
        assertThat(sqls).isNotEmpty();
        Path path = Path.of("test-artifacts/full-audit/s2/plans"); Files.createDirectories(path);
        boolean checked = false; int count = 0;
        for (var sql : sqls) {
            String plan = jdbc.execute((ConnectionCallback<String>) connection -> {
                try (var statement = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql.getSql())) {
                    var mapped = sessions.getConfiguration().getMappedStatement("com.lingmao.scm.module.order.mapper.OrderMapper.selectRowPage");
                    new DefaultParameterHandler(mapped, sql.getParameterObject(), sql).setParameters(statement);
                    try (var rows = statement.executeQuery()) { rows.next(); return rows.getString(1); }
                }
            });
            Files.writeString(path.resolve(name + "-" + count + ".json"), plan);
            Files.writeString(path.resolve(name + "-" + count++ + "-sql.txt"), sql.getSql());
            if (sql.getSql().contains(required) && !sql.getSql().stripLeading().startsWith("SELECT COUNT")) {
                assertThat(plan).as(name + " actual partial index").contains(index); checked = true;
            }
        }
        assertThat(checked).as(name + " bounded production SQL was explained").isTrue();
    }
}
