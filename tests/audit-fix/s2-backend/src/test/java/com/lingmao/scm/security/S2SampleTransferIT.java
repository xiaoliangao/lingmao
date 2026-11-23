package com.lingmao.scm.security;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Import(S2SampleTransferIT.CaptureConfig.class)
class S2SampleTransferIT extends S2SampleFixture {
    @Autowired StringRedisTemplate redis;
    @Autowired DeploymentNamespace namespace;
    @Autowired TransferCapture capture;

    @Test void writerThatReadTheOldSampleOwnerGetsStateConflictAfterConcurrentConversionCommits() throws Exception {
        try {
            Fixture f=fixture();Account actor=sampleActor(f);long sample=id(createSample(actor,sampleBody(f,actor)).path("id"));
            long pack=createSamplePack(actor,f.company(),sample);int version=saveSamplePack(actor,f,pack,"Before ownership transfer","11.0000");
            dispatchInternal(actor,f.company(),sample,actor.id());completeSend(actor,f.company(),sample);JsonNode passed=reviewSample(actor,f.company(),sample,"PASS");
            capture.begin(sample);capture.pauseOwner(pack);
            JsonNode stale,converted;
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var writer=executor.submit(()->sampleCall(actor,"PUT","/api/tech-packs/"+pack,
                        packBody(f,version,"Must not overwrite after transfer","99.0000"),f.company(),UUID.randomUUID().toString()));
                assertThat(capture.ownerRead.await(10,TimeUnit.SECONDS)).as("real old OwnerIdentity has been read").isTrue();
                converted=sampleOk(actor,"POST","/api/samples/"+sample+"/convert?companyId="+f.company(),
                        map("version",passed.path("sampleVersionLock").intValue(),"codeMode","MANUAL","code","S2"+suffix().toUpperCase(),"styleClass","FS","name","S2 concurrent ownership transfer"),f.company());
                capture.resumeOwner.countDown();stale=writer.get(20,TimeUnit.SECONDS);
            } finally {capture.resumeOwner.countDown();}
            evidence("transfer-waiting-writer.json",map("firstActualOwner",capture.firstOwner,"oldOwnerConnection",capture.ownerPid,
                    "conversionConnection",capture.conversionPid,"conversion",converted,"staleWriter",stale,
                    "actualPack",jdbc.queryForList("SELECT id,style_id,sample_id,version_lock,version_note FROM tp_tech_pack WHERE id=?",pack)));
            assertThat(capture.ownerPid).isPositive();assertThat(capture.conversionPid).isPositive().isNotEqualTo(capture.ownerPid);
            assertThat(capture.firstOwner.get("styleId")).isNull();
            assertThat(stale.path("code").intValue()).isEqualTo(1002);
            assertThat(jdbc.queryForObject("SELECT version_note FROM tp_tech_pack WHERE id=?",String.class,pack)).isEqualTo("Before ownership transfer");
            assertThat(jdbc.queryForObject("SELECT style_id FROM tp_tech_pack WHERE id=?",Long.class,pack)).isEqualTo(id(converted.path("styleId")));
        } finally {capture.end();saveSampleHttp("transfer-waiting-writer");}
    }

    @Test void fiftyThousandActualLightPackHeadsTransferWithOneEpochEventAndBoundedDatabaseCalls() throws Exception {
        try {
            Fixture f=fixture();Account actor=sampleActor(f);long sample=id(createSample(actor,sampleBody(f,actor)).path("id"));
            dispatchInternal(actor,f.company(),sample,actor.id());completeSend(actor,f.company(),sample);JsonNode passed=reviewSample(actor,f.company(),sample,"PASS");
            int inserted=jdbc.update("""
                    INSERT INTO tp_tech_pack(sample_id,stage,version,status,create_by,update_by)
                    SELECT ?,'DEV',g,CASE WHEN g=50000 THEN 'DRAFT' ELSE 'VOIDED' END,?,?
                    FROM generate_series(1,50000) g
                    """,sample,actor.id(),actor.id());
            assertThat(inserted).isEqualTo(50_000);
            long draft=jdbc.queryForObject("SELECT id FROM tp_tech_pack WHERE sample_id=? AND status='DRAFT'",Long.class,sample);
            JsonNode tagged=sampleOk(actor,"POST","/api/tags/objects/TECH_PACK/"+draft+"/replace",
                    map("version",0,"names",List.of("#S2 transfer "+suffix()),"tagVersions",Map.of(),"retainIds",List.of()),f.company());
            sampleOk(actor,"GET","/api/tags/objects/TECH_PACK/"+draft,null,f.company());
            String epochKey=namespace.sharedKey("public-tags-v1","library-epoch"),beforeEpoch=primeLibraryEpoch(actor);
            assertThat(beforeEpoch).isNotNull();long beforeEvents=events();
            capture.begin(sample);
            JsonNode converted=sampleOk(actor,"POST","/api/samples/"+sample+"/convert?companyId="+f.company(),
                    map("version",passed.path("sampleVersionLock").intValue(),"codeMode","MANUAL","code","S2"+suffix().toUpperCase(),"styleClass","FS","name","S2 bounded transfer"),f.company());
            capture.end();long style=id(converted.path("styleId"));
            long afterEvents=events();
            assertThat(afterEvents-beforeEvents).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tp_tech_pack WHERE sample_id=? AND style_id=?",Integer.class,sample,style)).isEqualTo(50_000);
            assertThat(jdbc.queryForObject("SELECT min(version_lock) FROM tp_tech_pack WHERE sample_id=?",Integer.class,sample)).isEqualTo(1);
            assertThat(capture.mapperCalls.getOrDefault("lockSamplePacks",new AtomicInteger()).get()).isEqualTo(1);
            assertThat(capture.mapperCalls.getOrDefault("adoptSamplePacks",new AtomicInteger()).get()).isEqualTo(1);
            assertThat(capture.mapperCalls.values().stream().mapToInt(AtomicInteger::get).sum()).isLessThan(20);
            assertThat(capture.tagRepositoryCalls.get()).isZero();
            assertThat(redis.opsForValue().get(epochKey)).as("actual after-commit invalidation removes the primed library epoch").isNull();
            JsonNode after=sampleOk(actor,"GET","/api/tags/objects/TECH_PACK/"+draft,null,null);
            assertThat(after).isEqualTo(tagged);
            String nextEpoch=primeLibraryEpoch(actor);assertThat(nextEpoch).isNotEqualTo(beforeEpoch);
            assertThat(jdbc.queryForObject("SELECT company_id FROM biz_tag_object WHERE tenant_key=? AND object_type='TECH_PACK' AND object_id=?",Long.class,namespace.tenant(),draft)).isNull();
            evidence("pack-transfer-50000.json",map("actualInsertedHeads",inserted,"adopted",50_000,"cacheEventsDelta",afterEvents-beforeEvents,
                    "epochChanged",!beforeEpoch.equals(nextEpoch),"mapperCalls",capture.counts(),"tagRepositoryCalls",capture.tagRepositoryCalls.get(),
                    "businessResponse",converted,"actualTagReadAfterTransfer",after));
        } finally {capture.end();saveSampleHttp("pack-transfer-50000");}
    }

    @Test void failedConversionRollsBackStyleSkusSerialPackOwnershipTagsAndCacheThenSameKeyCanSucceed() throws Exception {
        String function="s2_convert_fail_"+suffix();long target=0;
        try {
            Fixture f=fixture();Account actor=sampleActor(f);
            String designerNo=jdbc.queryForObject("SELECT lpad(g::text,2,'0') FROM generate_series(1,99) g WHERE NOT EXISTS(SELECT 1 FROM sys_user WHERE designer_no=lpad(g::text,2,'0')) ORDER BY g LIMIT 1",String.class);
            jdbc.update("UPDATE sys_user SET designer_no=? WHERE id=?",designerNo,actor.id());
            long sample=id(createSample(actor,sampleBody(f,actor)).path("id"));target=sample;
            long pack=createSamplePack(actor,f.company(),sample);int version=saveSamplePack(actor,f,pack,"S2 atomic conversion","11.0000");
            sampleOk(actor,"POST","/api/tech-packs/"+pack+"/publish",Map.of("versionLock",version),f.company());
            long draft=id(sampleOk(actor,"POST","/api/tech-packs/"+pack+"/revise",null,f.company()));
            JsonNode tags=sampleOk(actor,"POST","/api/tags/objects/TECH_PACK/"+draft+"/replace",
                    map("version",0,"names",List.of("#S2 rollback "+suffix()),"tagVersions",Map.of(),"retainIds",List.of()),f.company());
            sampleOk(actor,"GET","/api/tags/objects/TECH_PACK/"+draft,null,f.company());
            dispatchInternal(actor,f.company(),sample,actor.id());completeSend(actor,f.company(),sample);JsonNode passed=reviewSample(actor,f.company(),sample,"PASS");
            String epochKey=namespace.sharedKey("public-tags-v1","library-epoch"),epoch=primeLibraryEpoch(actor);long beforeEvents=events();
            String serialKey="FS"+today().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))+designerNo;
            var serialBefore=jdbc.queryForList("SELECT seq FROM sys_serial WHERE seq_key=?",Integer.class,serialKey);
            var packsBefore=jdbc.queryForList("SELECT id,style_id,version_lock FROM tp_tech_pack WHERE sample_id=? ORDER BY id",sample);
            long styleCount=jdbc.queryForObject("SELECT count(*) FROM sty_style",Long.class);
            jdbc.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id="+sample+" AND NEW.status='CONVERTED' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='synthetic conversion commit fault'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER "+function+" AFTER UPDATE ON dev_sample FOR EACH ROW EXECUTE FUNCTION "+function+"()");
            String path="/api/samples/"+sample+"/convert?companyId="+f.company(),key=UUID.randomUUID().toString();
            var request=map("version",passed.path("sampleVersionLock").intValue(),"codeMode","AUTO","styleClass","FS","name","S2 atomic style");
            JsonNode failed=sampleCall(actor,"POST",path,request,f.company(),key);
            assertThat(failed.path("code").intValue()).isEqualTo(500);
            assertThat(failed.toString()).doesNotContain("P0001","synthetic conversion commit fault","dev_sample","Exception");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sty_style",Long.class)).isEqualTo(styleCount);
            assertThat(jdbc.queryForList("SELECT seq FROM sys_serial WHERE seq_key=?",Integer.class,serialKey)).isEqualTo(serialBefore);
            assertThat(jdbc.queryForList("SELECT id,style_id,version_lock FROM tp_tech_pack WHERE sample_id=? ORDER BY id",sample)).isEqualTo(packsBefore);
            assertThat(redis.opsForValue().get(epochKey)).isEqualTo(epoch);assertThat(events()).isEqualTo(beforeEvents);
            assertThat(sampleOk(actor,"GET","/api/tags/objects/TECH_PACK/"+draft,null,f.company())).isEqualTo(tags);
            assertThat(jdbc.queryForObject("SELECT company_id FROM biz_tag_object WHERE tenant_key=? AND object_type='TECH_PACK' AND object_id=?",Long.class,namespace.tenant(),draft)).isEqualTo(f.company());
            jdbc.execute("DROP TRIGGER "+function+" ON dev_sample");jdbc.execute("DROP FUNCTION "+function+"()");
            JsonNode success=sampleCall(actor,"POST",path,request,f.company(),key);assertThat(success.path("code").intValue()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sty_style",Long.class)).isEqualTo(styleCount+1);
            assertThat(events()-beforeEvents).isEqualTo(1);
            assertThat(redis.opsForValue().get(epochKey)).as("successful retry commits the invalidation").isNull();
            assertThat(primeLibraryEpoch(actor)).isNotEqualTo(epoch);
            evidence("conversion-rollback.json",map("failed",failed,"retry",success,"serialBefore",serialBefore,
                    "serialAfter",jdbc.queryForList("SELECT seq FROM sys_serial WHERE seq_key=?",Integer.class,serialKey),"beforePackOwners",packsBefore,
                    "rollbackEpochUnchanged",true,"rollbackOutboxUnchanged",true,"actualCacheEventsAfterRetry",events()-beforeEvents));
        } finally {
            if(target>0){jdbc.execute("DROP TRIGGER IF EXISTS "+function+" ON dev_sample");jdbc.execute("DROP FUNCTION IF EXISTS "+function+"()");}
            saveSampleHttp("conversion-rollback");
        }
    }

    /** SAMPLE/TECH_PACK reads use a DB snapshot; only a real library read primes its cache epoch. */
    private String primeLibraryEpoch(Account actor) throws Exception {
        sampleOk(actor,"GET","/api/tags?keyword=S2&size=1",null,null);
        String epoch=redis.opsForValue().get(namespace.sharedKey("public-tags-v1","library-epoch"));
        assertThat(epoch).as("actual tag-options HTTP read publishes a nonempty library epoch").isNotBlank();
        return epoch;
    }

    private long events(){return jdbc.queryForObject("SELECT count(*) FROM sys_outbox WHERE tenant_key=? AND kind='CACHE_INVALIDATE'",Long.class,namespace.tenant());}
    private void evidence(String name,Object value) throws Exception {Path p=Path.of("target/a2-sample/transfers",name);Files.createDirectories(p.getParent());Files.writeString(p,json.writerWithDefaultPrettyPrinter().writeValueAsString(value));}

    static final class TransferCapture {
        volatile long sample;volatile String trace;
        volatile long pausePack;volatile int ownerPid,conversionPid;volatile Map<String,Object> firstOwner;
        final AtomicBoolean ownerPaused=new AtomicBoolean();
        volatile CountDownLatch ownerRead=new CountDownLatch(0),resumeOwner=new CountDownLatch(0);
        final Map<String,AtomicInteger> mapperCalls=new ConcurrentHashMap<>();final AtomicInteger tagRepositoryCalls=new AtomicInteger();
        void begin(long value){sample=value;trace=null;mapperCalls.clear();tagRepositoryCalls.set(0);conversionPid=0;}
        void pauseOwner(long pack){pausePack=pack;ownerPid=0;firstOwner=null;ownerPaused.set(false);ownerRead=new CountDownLatch(1);resumeOwner=new CountDownLatch(1);}
        void end(){sample=0;trace=null;pausePack=0;resumeOwner.countDown();}
        boolean current(){return trace!=null&&trace.equals(MDC.get("traceId"));}
        Map<String,Integer> counts(){var result=new java.util.TreeMap<String,Integer>();mapperCalls.forEach((k,v)->result.put(k,v.get()));return result;}
    }
    @Aspect static final class ActualCalls {
        final TransferCapture capture;final JdbcTemplate jdbc;
        ActualCalls(TransferCapture capture,JdbcTemplate jdbc){this.capture=capture;this.jdbc=jdbc;}
        @Around("execution(* com.lingmao.scm.module.dev.service.impl.SampleServiceImpl.convert(..))")
        Object trace(ProceedingJoinPoint p) throws Throwable {
            if(capture.sample>0&&((Long)p.getArgs()[1])==capture.sample){capture.trace=MDC.get("traceId");
                jdbc.execute("SET LOCAL log_parameter_max_length=0");jdbc.execute("SET LOCAL log_statement='all'");
                capture.conversionPid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);}
            return p.proceed();
        }
        @Around("bean(techPackMapper)") Object mapper(ProceedingJoinPoint p) throws Throwable {
            String method=((MethodSignature)p.getSignature()).getMethod().getName();
            if(capture.current())capture.mapperCalls.computeIfAbsent(method,k->new AtomicInteger()).incrementAndGet();
            Object result=p.proceed();
            if(method.equals("ownerIdentity") && capture.pausePack>0 && ((Number)p.getArgs()[0]).longValue()==capture.pausePack
                    && capture.ownerPaused.compareAndSet(false,true)) {
                var owner=(com.lingmao.scm.module.techpack.mapper.TechPackMapper.OwnerIdentity)result;
                capture.ownerPid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                capture.firstOwner=map("id",owner.id(),"styleId",owner.styleId(),"sampleId",owner.sampleId(),"companyId",owner.companyId(),"versionLock",owner.versionLock());
                jdbc.execute("SET LOCAL log_parameter_max_length=0");jdbc.execute("SET LOCAL log_statement='all'");
                capture.ownerRead.countDown();
                if(!capture.resumeOwner.await(20,TimeUnit.SECONDS))throw new AssertionError("Real conversion did not release the old owner read");
            }
            return result;
        }
        @Around("execution(* com.lingmao.scm.module.biz.repository.TagRepository.*(..))") Object tags(ProceedingJoinPoint p) throws Throwable {
            if(capture.current())capture.tagRepositoryCalls.incrementAndGet();return p.proceed();
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class CaptureConfig {
        @Bean TransferCapture transferCapture(){return new TransferCapture();}
        @Bean ActualCalls actualTransferCalls(TransferCapture capture,JdbcTemplate jdbc){return new ActualCalls(capture,jdbc);}
    }
}
