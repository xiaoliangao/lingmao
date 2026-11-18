package com.lingmao.scm.config;

import com.lingmao.scm.IntegrationTestConfig;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.service.OutboxService;
import com.lingmao.scm.module.system.service.impl.OutboxDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"lingmao.proxy.trusted-cidrs=","lingmao.observability.slow-sql-ms=20"})
@ActiveProfiles("it") @Import(IntegrationTestConfig.class) @ExtendWith(OutputCaptureExtension.class)
class A4AuditHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc; @Autowired JsonMapper json; @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager manager; @Autowired OutboxService outbox; @Autowired OutboxDispatcher dispatcher;
    @Autowired DeploymentNamespace namespace;
    private static final String PASSWORD="A4-Synthetic-Login-9374";

    @Test void actualHttpChangeRecordsPreciseDiffActorRealIpAndBothLogLayers() throws Exception {
        Account account=account(true);
        try(var http=HttpClient.newHttpClient()) {
            String code=UUID.randomUUID().toString().substring(0,6).replace('-','A');
            long id=Long.parseLong(data(send(http,"POST","/api/companies",Map.of("code",code,"name","A4旧名称","shortName","A4旧"),account.token,"a4-create-"+UUID.randomUUID())).asText());
            String trace="a4-update-"+UUID.randomUUID();
            var update=send(http,"PUT","/api/companies/"+id,Map.of("code",code,"name","A4新名称","shortName","A4新"),account.token,trace);
            assertThat(json.readTree(update.body()).get("code").intValue()).isZero();
            assertThat(update.headers().firstValue("X-Request-Id")).contains(trace);
            assertThat(update.headers().firstValue("X-Trace-Id")).contains(trace);
            var row=jdbc.queryForMap("SELECT * FROM sys_data_log WHERE table_name='bas_company' AND row_id=? AND action='UPDATE'",id);
            JsonNode diff=json.readTree(row.get("diff").toString());
            assertThat(diff.get("name").get("old").asText()).isEqualTo("A4旧名称");
            assertThat(diff.get("name").get("new").asText()).isEqualTo("A4新名称");
            assertThat(diff.has("update_time")).isFalse();
            assertThat(row.get("actor_user_id")).isEqualTo(account.id);assertThat(row.get("client_ip")).isEqualTo("127.0.0.1");
            assertThat(row.get("request_id")).isEqualTo(trace); assertThat(row.get("created_at")).isNotNull();
            assertThat(jdbc.queryForObject("SELECT ip FROM sys_oper_log WHERE trace_id=?",String.class,trace)).isEqualTo("127.0.0.1");
            assertThat(jdbc.queryForObject("SELECT params FROM sys_oper_log WHERE trace_id=?",String.class,trace)).doesNotContain("A4新名称");
            JsonNode response=data(send(http,"GET","/api/data-logs?table=bas_company&rowId="+id,null,account.token,trace));
            assertThat(response.get(0).get("actorUserId").isString()).isTrue();
            Path output=Path.of("target/test-artifacts/a4/audit-http.json");Files.createDirectories(output.getParent());
            Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(response));
        }
    }

    @Test void supplierBankDiffRequiresIndependentFieldPermissionAndRollbackLeavesNoAudit() throws Exception {
        Account owner=account(true), reviewer=account(false);
        try(var http=HttpClient.newHttpClient()) {
            String category=jdbc.queryForObject("SELECT value FROM sys_dict_data WHERE type_code='supplier_category' AND NOT deleted ORDER BY id LIMIT 1",String.class);
            var initial=new java.util.LinkedHashMap<String,Object>();
            initial.put("name","A4审计供应商"+UUID.randomUUID());initial.put("shortName","A4审计");initial.put("categories",List.of(category));
            initial.put("bankAccount","6222020000000011111"); initial.put("phone","13800138000");
            long id=Long.parseLong(data(send(http,"POST","/api/suppliers",initial,owner.token,"a4-bank-create-"+UUID.randomUUID())).get("id").asText());
            initial.put("bankAccount","6222020000000022222"); initial.put("phone","13900139000");
            String trace="a4-bank-update-"+UUID.randomUUID();
            assertThat(json.readTree(send(http,"PUT","/api/suppliers/"+id,initial,owner.token,trace).body()).get("code").intValue()).isZero();
            String path="/api/data-logs?table=sup_supplier&rowId="+id;
            String readTrace = "a4-bank-read-"+UUID.randomUUID();
            JsonNode permitted=data(send(http,"GET",path,null,owner.token,readTrace));
            assertThat(permitted.get(0).get("diff").get("bank_account").get("old").asText()).isEqualTo("6222020000000011111");
            assertThat(permitted.get(0).get("diff").get("bank_account").get("new").asText()).isEqualTo("6222020000000022222");
            var read = jdbc.queryForMap("SELECT user_id,ip,params FROM sys_oper_log WHERE trace_id=?", readTrace);
            assertThat(read.get("user_id")).isEqualTo(owner.id); assertThat(read.get("ip")).isEqualTo("127.0.0.1");
            assertThat(read.get("params").toString()).contains("sup_supplier", Long.toString(id)).doesNotContain("622202");
            jdbc.execute("CREATE FUNCTION a4_block_audit_read() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.method='DataLogService.list' THEN RAISE EXCEPTION 'audit destination unavailable'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER a4_block_audit_read BEFORE INSERT ON sys_oper_log FOR EACH ROW EXECUTE FUNCTION a4_block_audit_read()");
            try {
                var refused = send(http,"GET",path,null,owner.token,"a4-audit-unavailable");
                assertThat(json.readTree(refused.body()).path("code").intValue()).isEqualTo(500);
                assertThat(refused.body()).doesNotContain("622202", "bank_account", "audit destination");
            } finally { jdbc.execute("DROP TRIGGER a4_block_audit_read ON sys_oper_log"); jdbc.execute("DROP FUNCTION a4_block_audit_read()"); }
            String stripped=data(send(http,"GET",path,null,reviewer.token,"a4-bank-strip-"+UUID.randomUUID())).toString();
            assertThat(stripped).doesNotContain("bank_account","phone","622202","13900139000");
            assertThat(jdbc.queryForObject("SELECT request_id FROM biz_doc_log WHERE doc_type='SUPPLIER' AND doc_id=? ORDER BY id DESC LIMIT 1",String.class,id)).isEqualTo(trace);
            int before=jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE table_name='sup_supplier' AND row_id=?",Integer.class,id);
            new TransactionTemplate(manager).executeWithoutResult(status->{jdbc.update("UPDATE sup_supplier SET short_name='rolled-back' WHERE id=?",id);status.setRollbackOnly();});
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE table_name='sup_supplier' AND row_id=?",Integer.class,id)).isEqualTo(before);
            for(String table:List.of("sys_data_log","sys_oper_log"))
                for(String mutation:List.of("DELETE FROM "+table+" WHERE false","UPDATE "+table+" SET id=id WHERE false","TRUNCATE "+table))
                    assertThatThrownBy(()->jdbc.execute(mutation)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            int history=jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_id=? AND doc_type='SUPPLIER'",Integer.class,id);
            assertThat(jdbc.update("DELETE FROM biz_doc_log WHERE doc_id=? AND doc_type='SUPPLIER'",id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_id=? AND doc_type='SUPPLIER'",Integer.class,id)).isEqualTo(history);
            assertThatThrownBy(()->jdbc.execute("UPDATE biz_doc_log SET id=id WHERE false")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.execute("TRUNCATE biz_doc_log")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }

    @Test void databaseFailureIsSafeAndSlowSqlAndOutboxKeepTheSameRequestId(CapturedOutput output) throws Exception {
        Account account=account(true);String trace="trace-20261118001234567";
        String code=UUID.randomUUID().toString().substring(0,6).toUpperCase(java.util.Locale.ROOT);
        long id=jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES(?,?,?) RETURNING id",Long.class,code,"before","before");
        jdbc.execute("CREATE FUNCTION a4_reject_company() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.name='A4_FAIL' THEN PERFORM pg_sleep(0.05); RAISE EXCEPTION 'password=Sensitive-9374 bankAccount=6222023333333333333 SELECT hidden_table'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER a4_reject_company BEFORE UPDATE ON bas_company FOR EACH ROW EXECUTE FUNCTION a4_reject_company()");
        try(var http=HttpClient.newHttpClient()) {
            var response=send(http,"PUT","/api/companies/"+id,Map.of("code",code,"name","A4_FAIL","shortName","after"),account.token,trace);
            JsonNode error=json.readTree(response.body()); assertThat(error.get("code").intValue()).isEqualTo(500);
            assertThat(error.get("msg").asText()).contains(trace);
            assertThat(response.body()).doesNotContain("hidden_table","Sensitive-9374","SQLException","stack");
            assertThat(jdbc.queryForObject("SELECT name FROM bas_company WHERE id=?",String.class,id)).isEqualTo("before");
            assertThat(output.getAll()).contains("slow_sql",trace).doesNotContain("Sensitive-9374","6222023333333333333","SELECT hidden_table");
        } finally {jdbc.execute("DROP TRIGGER a4_reject_company ON bas_company");jdbc.execute("DROP FUNCTION a4_reject_company()");}
        long job=outbox.enqueue("CACHE_INVALIDATE","a4-"+UUID.randomUUID(),json.writeValueAsString(namespace.sharedKey("dict","a4")),account.id,trace);
        // The real scheduler/handler/Redis run; only test-profile automatic startup is disabled.
        var enabled=new OutboxDispatcher((com.lingmao.scm.module.system.service.impl.OutboxStore)outbox,
                List.of(new com.lingmao.scm.module.system.cache.CacheInvalidationHandler(sharedCache,json)),List.of(),true);
        try {
            enabled.start();long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while(!outbox.state(job).status().equals("SUCCEEDED") && System.nanoTime()<deadline)Thread.sleep(25);
            assertThat(outbox.state(job).status()).isEqualTo("SUCCEEDED");
            assertThat(output.getAll()).contains("outbox_completed",trace);
        } finally {enabled.stop();}
    }
    @Autowired com.lingmao.scm.module.system.cache.SharedCache sharedCache;

    @Test void probesArePublicAndLiveDoesNotBorrowDatabaseConnection() throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            assertThat(send(http,"GET","/health/live",null,null,"a4-health-live").statusCode()).isEqualTo(200);
            assertThat(send(http,"GET","/health/ready",null,null,"a4-health-ready").statusCode()).isEqualTo(200);
        }
    }
    private Account account(boolean financial) throws Exception {
        String username="a4-"+UUID.randomUUID().toString().substring(0,12);
        long id=jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES(?,?,?) RETURNING id",Long.class,username,passwords.encode(PASSWORD),"A4审计测试");
        var grants=new ArrayList<>(List.of("basic:basics:add","basic:basics:edit","supplier:supplier:add","supplier:supplier:edit","supplier:supplier:list","system:audit:query","data:all"));
        if(financial)grants.addAll(List.of("supplier:supplier:bank","supplier:supplier:contact","techpack:cost:query","order:price:query","production:price:query"));
        TestIdentity.grant(jdbc,id,grants);
        try(var http=HttpClient.newHttpClient()) {return new Account(id,data(send(http,"POST","/api/auth/login",Map.of("username",username,"password",PASSWORD),null,"a4-login-"+UUID.randomUUID())).asText());}
    }
    private JsonNode data(HttpResponse<String> response) {JsonNode value=json.readTree(response.body());assertThat(value.get("code").intValue()).isZero();return value.get("data");}
    private HttpResponse<String> send(HttpClient http,String method,String path,Object body,String token,String trace) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type","application/json").header("X-Request-Id",trace).header("X-Forwarded-For","203.0.113.92");
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(method.equals("POST") && !path.contains("auth"))request.header("Idempotency-Key",UUID.randomUUID().toString());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private record Account(long id,String token){}
}
