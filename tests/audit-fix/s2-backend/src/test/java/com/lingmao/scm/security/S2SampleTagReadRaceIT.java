package com.lingmao.scm.security;

import com.lingmao.scm.module.dev.entity.Sample;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP owner reassignment and tag writes while the former maker's actual authorization query is paused. */
@Import(S2SampleTagReadRaceIT.TagReadConfig.class)
class S2SampleTagReadRaceIT extends S2SampleFixture {
    @Autowired TagReadGate gate;

    @ParameterizedTest @CsvSource({"SAMPLE,false","SAMPLE,true","TECH_PACK,false","TECH_PACK,true"})
    void formerMakerCannotReceiveTagsAddedAfterItsVisibilityWasRemoved(String type,boolean warmNewPayload) throws Exception {
        Fixture f=fixture();Account editor=sampleActor(f);
        Account reader=custom(f.company(),"SELF","dev:sample:list","techpack:pack:list");
        var body=sampleBody(f,editor);body.put("patternMakerId",reader.id());
        long sample=id(createSample(editor,body).path("id"));
        long target=type.equals("SAMPLE")?sample:createSamplePack(editor,f.company(),sample);
        String path="/api/tags/objects/"+type+"/"+target;
        String oldName="before-ownership-"+suffix(),newName="after-ownership-"+suffix();
        JsonNode before=sampleOk(editor,"POST",path+"/replace",
                map("version",0,"names",List.of(oldName),"tagVersions",Map.of(),"retainIds",List.of()),f.company());
        assertThat(sampleOk(reader,"GET",path,null,f.company())).isEqualTo(before);
        JsonNode detail=sampleDetail(editor,f.company(),sample),order=detail.path("currentOrder");
        var reassignment=map("version",order.path("version").intValue(),"sampleVersionLock",detail.path("version").intValue(),
                "sampleType","FIRST","qty",1,"colorIds",List.of(f.color()),"sizeCodes",List.of("M"),
                "needDate",today().plusDays(10),"makeWay","INTERNAL","makerId",editor.id());
        String trace="sample-tag-read-"+suffix();State state=new State(sample,trace);gate.current=state;
        HttpResponse<byte[]> raw;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var request=pool.submit(()->http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                    .timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+reader.token())
                    .header("X-Company-Id",Long.toString(f.company())).header("X-Request-Id",trace).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()));
            try {
                assertThat(state.authorized.await(8,TimeUnit.SECONDS)).as("the real authorized row was read before the competing writes").isTrue();
                sampleOk(editor,"PUT","/api/sample-orders/"+id(order.path("id"))+"?companyId="+f.company(),reassignment,f.company());
                JsonNode current=sampleOk(editor,"POST",path+"/replace",
                        map("version",before.path("version").intValue(),"names",List.of(newName),
                                "tagVersions",Map.of(before.path("tags").get(0).path("id").stringValue(),before.path("tags").get(0).path("version").intValue()),
                                "retainIds",List.of()),f.company());
                assertThat(current.path("tags").get(0).path("name").stringValue()).isEqualTo(newName);
                if(warmNewPayload)assertThat(sampleOk(editor,"GET",path,null,f.company())).isEqualTo(current);
            } finally {state.resume.countDown();}
            raw=request.get(25,TimeUnit.SECONDS);
        } finally {state.resume.countDown();gate.current=null;}
        JsonNode reply=node(raw),later=sampleCall(reader,"GET",path,null,f.company(),null);
        Path directory=Path.of("target/a2-sample/tag-read-race");Files.createDirectories(directory);
        Files.write(directory.resolve(type.toLowerCase()+"-"+warmNewPayload+"-response.json"),raw.body());
        Files.writeString(directory.resolve(type.toLowerCase()+"-"+warmNewPayload+"-interleaving.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(map("type",type,"targetId",Long.toString(target),
                        "readTrace",trace,"readBackendPid",state.readPid,"writeBackendPid",state.writePid,
                        "firstAuthorizedSampleId",state.firstSample,"firstAuthorizedDesigner",state.firstDesigner,
                        "readerPayloadDatabaseLoads",state.payloadReads.get(),"newPayloadWarmed",warmNewPayload,
                        "httpStatus",raw.statusCode(),"initialPayload",before,"oldRequestResponse",reply,"laterResponse",later,
                        "actualTimeline",jdbc.queryForList("SELECT id,action,request_id,remark FROM biz_doc_log WHERE doc_type=? AND doc_id=? ORDER BY id",type,target))));
        saveSampleHttp("tag-read-race-"+type.toLowerCase().replace('_','-')+"-"+warmNewPayload);
        assertThat(state.readPid).isNotNull().isNotEqualTo(state.writePid);assertThat(state.writePid).isNotNull();
        assertThat(later.path("code").intValue()).isEqualTo(404);
        // A complete old snapshot or a fresh authorization refusal are valid; a newly committed secret is never valid.
        assertThat(reply.path("code").intValue()).isIn(0,404);
        if(reply.path("code").intValue()==0)assertThat(reply.path("data")).isEqualTo(before);
        assertThat(reply.toString()).doesNotContain(newName);
    }

    static final class State {
        final long sample;final String trace;
        final CountDownLatch authorized=new CountDownLatch(1),resume=new CountDownLatch(1);
        final AtomicBoolean paused=new AtomicBoolean();final AtomicInteger payloadReads=new AtomicInteger();
        volatile Integer readPid,writePid;volatile Long firstSample,firstDesigner;
        State(long sample,String trace){this.sample=sample;this.trace=trace;}
    }
    static final class TagReadGate {volatile State current;}
    @Aspect static final class TagReadBarrier {
        final TagReadGate gate;final JdbcTemplate jdbc;
        TagReadBarrier(TagReadGate gate,JdbcTemplate jdbc){this.gate=gate;this.jdbc=jdbc;}
        @Around("execution(* com.lingmao.scm.module.dev.mapper.SampleMapper.selectVisible(..))")
        Object afterRealScopeQuery(ProceedingJoinPoint call) throws Throwable {
            Object result=call.proceed();State state=gate.current;
            if(state!=null&&result instanceof Sample row&&row.getId()==state.sample&&state.trace.equals(MDC.get("traceId"))&&state.paused.compareAndSet(false,true)){
                state.firstSample=row.getId();state.firstDesigner=row.getDesignerId();
                state.readPid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                jdbc.execute("SET LOCAL log_parameter_max_length=0");jdbc.execute("SET LOCAL log_statement='all'");
                state.authorized.countDown();
                if(!state.resume.await(15,TimeUnit.SECONDS))throw new AssertionError("Timed out waiting for committed owner and tag writes");
            }
            return result;
        }
        @Around("execution(* com.lingmao.scm.module.dev.mapper.SampleMapper.lockVisible(..))")
        Object captureRealWriter(ProceedingJoinPoint call) throws Throwable {
            Object result=call.proceed();State state=gate.current;
            if(state!=null&&result instanceof Sample row&&row.getId()==state.sample&&state.writePid==null){
                state.writePid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                jdbc.execute("SET LOCAL log_parameter_max_length=0");jdbc.execute("SET LOCAL log_statement='all'");
            }
            return result;
        }
        @Around("execution(* com.lingmao.scm.module.biz.repository.TagRepository.read(..))")
        Object countActualPayloadReads(ProceedingJoinPoint call) throws Throwable {
            State state=gate.current;if(state!=null&&state.trace.equals(MDC.get("traceId")))state.payloadReads.incrementAndGet();
            return call.proceed();
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class TagReadConfig {
        @Bean TagReadGate tagReadGate(){return new TagReadGate();}
        @Bean TagReadBarrier sampleTagReadBarrier(TagReadGate gate,JdbcTemplate jdbc){return new TagReadBarrier(gate,jdbc);}
    }
}
