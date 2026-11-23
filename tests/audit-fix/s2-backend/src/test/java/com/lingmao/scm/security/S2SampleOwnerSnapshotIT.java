package com.lingmao.scm.security;

import com.lingmao.scm.module.dev.dto.SampleUpdateReq;
import com.lingmao.scm.module.dev.service.SampleService;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import com.lingmao.scm.module.techpack.service.TechPackService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** One real HTTP read and a second real connection invoking the production public write services. */
@Import(S2SampleOwnerSnapshotIT.SnapshotConfig.class)
class S2SampleOwnerSnapshotIT extends S2SampleFixture {
    @Autowired SnapshotGate gate;
    @Autowired SampleService samples;
    @Autowired TechPackService packs;
    @Autowired PlatformTransactionManager transactions;

    @ParameterizedTest @ValueSource(strings={"logs","detail","cost","pdf"})
    void authorizationAndBodyStayInOneSnapshotWhileAnotherConnectionTransfersOwnership(String kind) throws Exception {
        Fixture f=fixture();Account editor=sampleActor(f);
        Account reader=custom(f.company(),"SELF","dev:sample:list","techpack:pack:list","techpack:cost:query");
        var body=sampleBody(f,editor);body.put("designerId",reader.id());long sample=id(createSample(editor,body).path("id"));
        long pack=createSamplePack(editor,f.company(),sample);saveSamplePack(editor,f,pack,"before-transfer","11.0000");
        JsonNode before=sampleDetail(editor,f.company(),sample);
        JsonNode packBefore=sampleOk(editor,"GET","/api/tech-packs/"+pack,null,f.company());
        var beforeLogIds=jdbc.queryForList("SELECT id FROM biz_doc_log WHERE doc_type='TECH_PACK' AND doc_id=? ORDER BY id DESC",Long.class,pack);
        String readTrace="sample-read-"+suffix(),editTrace="sample-write-"+suffix();
        State state=new State(sample,readTrace);gate.current=state;
        var moved=S2SampleHttpIT.editable(body);moved.put("version",before.path("version").intValue());moved.put("designerId",editor.id());
        SampleUpdateReq change=json.convertValue(moved,SampleUpdateReq.class);
        TechPackSaveReq content=json.convertValue(packBody(f,packBefore.path("versionLock").intValue(),"after-transfer-secret","99.0000"),TechPackSaveReq.class);
        String suffixPath=kind.equals("detail")?"":kind.equals("pdf")?"/pdf?price=true&appendix=false":"/"+kind;
        String path="/api/tech-packs/"+pack+suffixPath;
        HttpResponse<byte[]> response;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var writer=pool.submit(()->{
                authenticate(editor);MDC.put("traceId",editTrace);
                try {
                    var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(20);
                    tx.executeWithoutResult(status->{
                        // Acquire B before the HTTP read so the authorization query necessarily uses another physical connection.
                        state.writePid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                        jdbc.execute("SET LOCAL log_parameter_max_length=0");jdbc.execute("SET LOCAL log_statement='all'");
                        state.writerReady.countDown();await(state.authorized,"actual owner authorization");
                        samples.update(f.company(),sample,change);
                        packs.save(pack,content);
                    });
                } finally {state.resume.countDown();MDC.clear();SecurityContextHolder.clearContext();}
            });
            assertThat(state.writerReady.await(5,TimeUnit.SECONDS)).isTrue();
            var read=pool.submit(()->{
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30))
                        .header("Authorization","Bearer "+reader.token()).header("X-Company-Id",Long.toString(f.company()))
                        .header("X-Request-Id",readTrace).GET().build();
                return http.send(request,HttpResponse.BodyHandlers.ofByteArray());
            });
            writer.get(25,TimeUnit.SECONDS);response=read.get(30,TimeUnit.SECONDS);
        } finally {state.resume.countDown();}
        Path directory=Path.of("target/a2-sample/snapshots");Files.createDirectories(directory);
        boolean pdf=response.headers().firstValue("Content-Type").orElse("").startsWith("application/pdf");
        // Keep the original download before attempting to parse it.
        Files.write(directory.resolve(kind+"-response."+(pdf?"pdf":"json")),response.body());
        String text;
        if(pdf){try(var document=Loader.loadPDF(response.body())){text=new PDFTextStripper().getText(document);}}
        else text=new String(response.body(),java.nio.charset.StandardCharsets.UTF_8);
        var following=sampleCall(reader,"GET",path,null,f.company(),null);
        var actualPack=jdbc.queryForMap("SELECT id,version_lock,version_note FROM tp_tech_pack WHERE id=?",pack);
        var actualLogs=jdbc.queryForList("SELECT id,action,request_id,remark FROM biz_doc_log WHERE doc_type='TECH_PACK' AND doc_id=? ORDER BY id",pack);
        Files.writeString(directory.resolve(kind+"-interleaving.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(
                map("readTrace",readTrace,"writeTrace",editTrace,"readBackend",state.readPid,"writeBackend",state.writePid,
                        "outerReadTransaction",state.outerTransaction,"readIsolation",state.isolation,"renderTransactionActive",state.renderTransaction,
                        "httpStatus",response.statusCode(),"readBody",pdf?text:json.readTree(response.body()),"laterReadResponse",following,
                        "actualCurrentPack",actualPack,"beforeLogIds",beforeLogIds,"actualCurrentLogs",actualLogs)));
        // In the old logs path this first assertion fails: the HTTP body includes the newly committed update log.
        if(kind.equals("logs")){
            var ids=new java.util.ArrayList<Long>();json.readTree(response.body()).path("data").forEach(row->ids.add(id(row.path("id"))));
            assertThat(ids).containsExactlyElementsOf(beforeLogIds);
        }
        assertThat(state.readPid).isNotEqualTo(state.writePid);
        assertThat(state.outerTransaction).isTrue();assertThat(state.isolation).isEqualTo("repeatable read");
        assertThat(response.statusCode()).isEqualTo(200);assertThat(following.path("code").intValue()).isEqualTo(404);
        assertThat(actualPack.get("version_note")).isEqualTo("after-transfer-secret");
        if(kind.equals("pdf")){
            assertThat(pdf).isTrue();assertThat(state.renderTransaction).isFalse();
            assertThat(text).contains("before-transfer").doesNotContain("after-transfer-secret");
        } else {
            JsonNode actual=json.readTree(response.body());assertThat(actual.path("code").intValue()).isZero();
            if(kind.equals("detail"))assertThat(actual.path("data").path("versionNote").stringValue()).isEqualTo("before-transfer");
            if(kind.equals("cost"))assertThat(actual.path("data").path("fabricRows").get(0).path("price").decimalValue()).isEqualByComparingTo("11.0000");
        }
        saveSampleHttp("owner-snapshot-"+kind);
    }

    private static void await(CountDownLatch latch,String stage) {
        try{if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("Timed out waiting for "+stage);}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
    }
    static final class State {
        final long sample;final String trace;
        final CountDownLatch writerReady=new CountDownLatch(1),authorized=new CountDownLatch(1),resume=new CountDownLatch(1);
        final AtomicBoolean paused=new AtomicBoolean();
        volatile Integer readPid,writePid;volatile boolean outerTransaction;volatile String isolation;volatile Boolean renderTransaction;
        State(long sample,String trace){this.sample=sample;this.trace=trace;}
    }
    static final class SnapshotGate {volatile State current;}
    @Aspect static final class ReadBarrier {
        final SnapshotGate gate;final JdbcTemplate jdbc;
        ReadBarrier(SnapshotGate gate,JdbcTemplate jdbc){this.gate=gate;this.jdbc=jdbc;}
        @Around("execution(* com.lingmao.scm.module.dev.service.impl.SamplePackOwnerImpl.checkReadable(..))")
        Object afterActualAuthorization(ProceedingJoinPoint call) throws Throwable {
            Object value=call.proceed();State state=gate.current;
            if(state!=null&&((Long)call.getArgs()[1])==state.sample&&state.trace.equals(MDC.get("traceId"))&&state.paused.compareAndSet(false,true)) {
                state.outerTransaction=TransactionSynchronizationManager.isActualTransactionActive();
                state.readPid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                state.isolation=jdbc.queryForObject("SHOW transaction_isolation",String.class);
                if(state.outerTransaction){jdbc.execute("SET LOCAL log_parameter_max_length=0");jdbc.execute("SET LOCAL log_statement='all'");}
                state.authorized.countDown();await(state.resume,"committed ownership and content update");
            }
            return value;
        }
        @Around("execution(* com.lingmao.scm.common.pdf.PdfRenderer.render(..))")
        Object renderOutsideTransaction(ProceedingJoinPoint call) throws Throwable {
            State state=gate.current;if(state!=null&&state.trace.equals(MDC.get("traceId")))state.renderTransaction=TransactionSynchronizationManager.isActualTransactionActive();
            return call.proceed();
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class SnapshotConfig {
        @Bean SnapshotGate snapshotGate(){return new SnapshotGate();}
        @Bean ReadBarrier sampleReadBarrier(SnapshotGate gate,JdbcTemplate jdbc){return new ReadBarrier(gate,jdbc);}
    }
}
