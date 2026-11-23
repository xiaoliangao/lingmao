package com.lingmao.scm.module.fg.core;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.SqlStatementCapture;
import com.lingmao.scm.module.fg.dto.FgStockLogQuery;
import com.lingmao.scm.module.fg.dto.FgStockLogRowVO;
import com.lingmao.scm.module.fg.mapper.FgStockLogMapper;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 两千条真实主体账，捕获并 EXPLAIN 生产 Mapper SQL；不关闭 seqscan 强迫索引。 */
@IntegrationTest
class S1FgSeekPlansIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired FgStockLogMapper logs;
    @Autowired SqlSessionFactory sessions;
    @Autowired JsonMapper json;

    @Test
    void allRowsRemainReachableBeyondFirstHundredAndSqlUsesCompanySeekIndex() throws Exception {
        long company=seed();
        jdbc.execute("ANALYZE fg_stock_log");jdbc.execute("ANALYZE fg_doc");jdbc.execute("ANALYZE sty_style_sku");jdbc.execute("ANALYZE sty_style");
        FgStockLogQuery query=new FgStockLogQuery();
        var capture=SqlStatementCapture.capture(() -> logs.selectSeek(query,null,company,null,null,101));
        assertThat(capture.statements()).hasSize(1);
        BoundSql first=capture.statements().getFirst();
        assertThat(first.getSql().toLowerCase()).doesNotContain("offset","count(");
        String plan=explain(first);
        assertThat(plan).contains("idx_fg_log_company_seek");
        Set<Long> ids=new HashSet<>();int pages=0;List<FgStockLogRowVO> rows=capture.result();
        while(true) {
            pages++;
            List<FgStockLogRowVO> consumed=rows.size()>100?rows.subList(0,100):rows;
            for(FgStockLogRowVO row:consumed) assertThat(ids.add(row.getId())).as("duplicate cursor row %s",row.getId()).isTrue();
            if(rows.size()<=100) break;
            FgStockLogRowVO last=consumed.getLast();
            rows=logs.selectSeek(query,null,company,last.getCreateTime(),last.getId(),101);
        }
        assertThat(ids).hasSize(2000);assertThat(pages).isEqualTo(20);
        // 相同时间戳有上千条，id 是必要的稳定第二排序键。
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT create_time) FROM fg_stock_log WHERE company_id=?",Integer.class,company)).isEqualTo(1);
        Path out=Path.of("target","a1-fg");Files.createDirectories(out);
        Files.writeString(out.resolve("seek-explain.json"),plan);
        Files.writeString(out.resolve("seek-result.json"),json.writeValueAsString(Map.of("seedRows",2000,"uniqueReturned",ids.size(),"pages",pages,"sql",first.getSql())));
    }

    private String explain(BoundSql sql) {
        var statement=sessions.getConfiguration().getMappedStatement("com.lingmao.scm.module.fg.mapper.FgStockLogMapper.selectSeek");
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            try(var prepared=connection.prepareStatement("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) "+sql.getSql())) {
                new DefaultParameterHandler(statement,sql.getParameterObject(),sql).setParameters(prepared);
                try(var result=prepared.executeQuery()) { assertThat(result.next()).isTrue(); return result.getString(1); }
            }
        });
    }

    private long seed() {
        return transactions.execute(status -> {
            String token="FGP"+UUID.randomUUID().toString().replace("-","").substring(0,12);
            long claimed=jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('bas_company','id'))",Long.class);
            String code="G"+Long.toString(claimed,36).toUpperCase(java.util.Locale.ROOT);
            long company=jdbc.queryForObject("INSERT INTO bas_company(id,code,name,short_name) OVERRIDING SYSTEM VALUE VALUES (?,?,?,'FG plans') RETURNING id",Long.class,claimed,code,token);
            long warehouse=jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES (?,?,'PRODUCT') RETURNING id",Long.class,token,token);
            long category=jdbc.queryForObject("INSERT INTO bas_category(name,level) VALUES (?,1) RETURNING id",Long.class,token);
            long group=jdbc.queryForObject("INSERT INTO bas_size_group(name,base_size) VALUES (?,'M') RETURNING id",Long.class,token);
            jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES (?,1,'M'),(?,2,'L')",group,group);
            long color=jdbc.queryForObject("INSERT INTO bas_color(code,name) VALUES ('PFG','FG plan color') ON CONFLICT(code) DO UPDATE SET code=EXCLUDED.code RETURNING id",Long.class);
            jdbc.update("INSERT INTO sty_style(code,name,style_class,brand,category_id,year,season,source,size_group_id,status) SELECT ?||n,'FG seek fixture','FS','S1',?,2026,'AUTUMN','OWN',?,'ENABLED' FROM generate_series(1,1000) n",token,category,group);
            jdbc.update("INSERT INTO sty_style_color(style_id,color_id,seq) SELECT id,?,1 FROM sty_style WHERE code LIKE ?",color,token+"%");
            jdbc.update("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) SELECT s.id,s.code||'-'||z.size_code,?,z.size_code FROM sty_style s CROSS JOIN (VALUES ('M'),('L')) z(size_code) WHERE s.code LIKE ?",color,token+"%");
            long doc=jdbc.queryForObject("INSERT INTO fg_doc(no,company_id,kind,doc_type,to_warehouse_id,doc_date,reason) VALUES (?,?,'IN','OTHER_IN',?,current_date,'OPENING') RETURNING id",Long.class,token,company,warehouse);
            jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty) SELECT ?,row_number() OVER (ORDER BY k.id),k.id,1 FROM sty_style_sku k JOIN sty_style s ON s.id=k.style_id WHERE s.code LIKE ?",doc,token+"%");
            jdbc.update("UPDATE fg_doc SET status='APPROVED',version=1 WHERE id=?",doc);
            jdbc.update("INSERT INTO fg_stock(company_id,warehouse_id,style_sku_id,qty,movement_revision) SELECT ?,?,style_sku_id,1,1 FROM fg_doc_item WHERE doc_id=?",company,warehouse,doc);
            jdbc.update("INSERT INTO fg_stock_log(fg_doc_id,company_id,change_type,doc_no,biz_date,warehouse_id,style_sku_id,change_qty,balance_qty) SELECT d.id,d.company_id,d.doc_type,d.no,d.doc_date,d.to_warehouse_id,i.style_sku_id,1,1 FROM fg_doc d JOIN fg_doc_item i ON i.doc_id=d.id WHERE d.id=?",doc);
            return company;
        });
    }
}
