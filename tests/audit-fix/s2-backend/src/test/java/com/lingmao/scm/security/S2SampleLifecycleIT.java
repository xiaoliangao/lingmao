package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class S2SampleLifecycleIT extends S2SampleFixture {
    @Test void externalDispatchUsesActualFactoryAndPlansAndBothVersionsGuardNodeUpdates() throws Exception {
        try {
            Fixture f=fixture();Account actor=sampleActor(f);long sample=id(createSample(actor,sampleBody(f,actor)).path("id"));
            JsonNode detail=sampleDetail(actor,f.company(),sample),order=detail.path("currentOrder");long orderId=id(order.path("id"));
            JsonNode preview=sampleOk(actor,"GET","/api/sample-orders/"+orderId+"/dispatch-preview?companyId="+f.company()+"&needDate="+today().plusDays(10),null,f.company());
            var dates=new ArrayList<String>();preview.path("nodes").forEach(n->dates.add(n.path("planDate").stringValue()));
            assertThat(dates).containsExactly(today().plusDays(3).toString(),today().plusDays(5).toString(),today().plusDays(6).toString(),today().plusDays(9).toString(),today().plusDays(10).toString());
            JsonNode dispatched=sampleOk(actor,"POST","/api/sample-orders/"+orderId+"/dispatch?companyId="+f.company(),
                    map("version",0,"sampleVersionLock",0,"makeWay","EXTERNAL","factoryId",f.supplier(),"needDate",today().plusDays(10)),f.company());
            assertThat(dispatched.path("sampleVersionLock").intValue()).isEqualTo(1);assertThat(dispatched.path("version").intValue()).isEqualTo(1);
            detail=sampleDetail(actor,f.company(),sample);order=detail.path("currentOrder");
            assertThat(order.path("makeWay").stringValue()).isEqualTo("EXTERNAL");assertThat(id(order.path("factoryId"))).isEqualTo(f.supplier());
            long node=id(order.path("nodes").get(0).path("id"));String path="/api/sample-orders/"+orderId+"/nodes/"+node+"/done?companyId="+f.company();
            assertThat(sampleCall(actor,"POST",path,map("version",0,"sampleVersionLock",1,"doneDate",today()),f.company(),UUID.randomUUID().toString()).path("code").intValue()).isEqualTo(1002);
            assertThat(sampleCall(actor,"POST",path,map("version",1,"sampleVersionLock",0,"doneDate",today()),f.company(),UUID.randomUUID().toString()).path("code").intValue()).isEqualTo(1002);
            assertThat(sampleCall(actor,"POST",path,map("version",1,"sampleVersionLock",1,"doneDate",today().plusDays(1)),f.company(),UUID.randomUUID().toString()).path("code").intValue()).isEqualTo(400);
            var plans=new ArrayList<Map<String,Object>>();for(JsonNode n:order.path("nodes"))plans.add(map("id",id(n.path("id")),"planDate",n.path("planDate").stringValue(),"ownerId",actor.id(),"remark","S2 plan"));
            JsonNode planned=sampleOk(actor,"PUT","/api/sample-orders/"+orderId+"/nodes?companyId="+f.company(),map("version",1,"sampleVersionLock",1,"nodes",plans),f.company());
            assertThat(planned.path("version").intValue()).isEqualTo(2);assertThat(planned.path("sampleVersionLock").intValue()).isEqualTo(2);
            JsonNode completed=sampleOk(actor,"POST",path,map("version",2,"sampleVersionLock",2,"doneDate",today()),f.company());
            JsonNode undone=sampleOk(actor,"DELETE",path+"&version="+completed.path("version").intValue()+"&sampleVersionLock="+completed.path("sampleVersionLock").intValue(),null,f.company());
            assertThat(undone.path("orderStatus").stringValue()).isEqualTo("MAKING");
            assertThat(jdbc.queryForObject("SELECT done_date FROM dev_sample_node WHERE id=?",java.time.LocalDate.class,node)).isNull();
            JsonNode sent=completeSend(actor,f.company(),sample);
            JsonNode waiting=sampleDetail(actor,f.company(),sample).path("currentOrder");
            assertThat(waiting.path("actions").path("edit").booleanValue()).isFalse();
            JsonNode rejectedFiles=sampleCall(actor,"PUT","/api/sample-orders/"+orderId+"/attachments?companyId="+f.company(),
                    map("version",sent.path("version").intValue(),"sampleVersionLock",sent.path("sampleVersionLock").intValue(),"files",List.of()),f.company(),UUID.randomUUID().toString());
            assertThat(rejectedFiles.path("code").intValue()).isEqualTo(1002);
            long pattern=id(waiting.path("nodes").get(1).path("id"));String patternPath="/api/sample-orders/"+orderId+"/nodes/"+pattern+"/done?companyId="+f.company();
            JsonNode reported=sampleOk(actor,"POST",patternPath,map("version",sent.path("version").intValue(),"sampleVersionLock",sent.path("sampleVersionLock").intValue(),"doneDate",today()),f.company());
            JsonNode corrected=sampleOk(actor,"POST",patternPath,map("version",reported.path("version").intValue(),"sampleVersionLock",reported.path("sampleVersionLock").intValue(),"doneDate",today().minusDays(1)),f.company());
            assertThat(corrected.path("orderStatus").stringValue()).isEqualTo("WAIT_REVIEW");
            assertThat(jdbc.queryForObject("SELECT done_date FROM dev_sample_node WHERE id=?",java.time.LocalDate.class,pattern)).isEqualTo(today().minusDays(1));
            JsonNode removedPattern=sampleOk(actor,"DELETE",patternPath+"&version="+corrected.path("version").intValue()+"&sampleVersionLock="+corrected.path("sampleVersionLock").intValue(),null,f.company());
            assertThat(removedPattern.path("orderStatus").stringValue()).isEqualTo("WAIT_REVIEW");
            JsonNode sendNode=null;for(JsonNode n:waiting.path("nodes"))if(n.path("nodeCode").stringValue().equals("SEND"))sendNode=n;
            assertThat(sendNode).isNotNull();
            JsonNode unsent=sampleOk(actor,"DELETE","/api/sample-orders/"+orderId+"/nodes/"+id(sendNode.path("id"))+"/done?companyId="+f.company()
                    +"&version="+removedPattern.path("version").intValue()+"&sampleVersionLock="+removedPattern.path("sampleVersionLock").intValue(),null,f.company());
            assertThat(unsent.path("orderStatus").stringValue()).isEqualTo("MAKING");
        } finally {saveSampleHttp("external-nodes-dual-cas");}
    }

    @Test void concurrentReviewAndStopCommitOnlyOneConsistentParentChildOutcome() throws Exception {
        try {
            Fixture f=fixture();Account first=sampleActor(f),second=sampleActor(f);long sample=id(createSample(first,sampleBody(f,first)).path("id"));
            dispatchInternal(first,f.company(),sample,first.id());completeSend(first,f.company(),sample);
            JsonNode detail=sampleDetail(first,f.company(),sample),order=detail.path("currentOrder");long orderId=id(order.path("id"));
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);List<JsonNode> replies;
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var review=pool.submit(()->{ready.countDown();start.await();return sampleCall(first,"POST","/api/sample-orders/"+orderId+"/review?companyId="+f.company(),
                        map("version",order.path("version").intValue(),"sampleVersionLock",detail.path("version").intValue(),"result","PASS","reviewDate",today(),"reviewerIds",List.of(first.id(),second.id()),"photos",List.of()),f.company(),UUID.randomUUID().toString());});
                var stop=pool.submit(()->{ready.countDown();start.await();return sampleCall(second,"POST","/api/samples/"+sample+"/stop?companyId="+f.company(),
                        map("version",detail.path("version").intValue(),"reason","Concurrent synthetic stop"),f.company(),UUID.randomUUID().toString());});
                assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();replies=List.of(review.get(20,TimeUnit.SECONDS),stop.get(20,TimeUnit.SECONDS));
            }
            assertThat(replies.stream().map(r->r.path("code").intValue()).sorted().toList()).containsExactly(0,1002);
            String pair=jdbc.queryForObject("SELECT s.status||':'||o.status FROM dev_sample s JOIN dev_sample_order o ON o.sample_id=s.id WHERE s.id=? ORDER BY o.sample_version DESC LIMIT 1",String.class,sample);
            assertThat(pair).isIn("PASSED:PASSED","ELIMINATED:CANCELLED");
            int reviews=jdbc.queryForObject("SELECT count(*) FROM dev_sample_review WHERE sample_order_id=?",Integer.class,orderId);
            assertThat(reviews).isEqualTo(pair.startsWith("PASSED")?1:0);
        } finally {saveSampleHttp("review-stop-race");}
    }

    @Test void twentyConcurrentRevisionAndConversionRequestsCreateOneNewVersionAndOneStyle() throws Exception {
        try {
            Fixture f=fixture();Account actor=sampleActor(f);long sample=id(createSample(actor,sampleBody(f,actor)).path("id"));
            dispatchInternal(actor,f.company(),sample,actor.id());completeSend(actor,f.company(),sample);
            JsonNode detail=sampleDetail(actor,f.company(),sample),order=detail.path("currentOrder");
            String revisePath="/api/sample-orders/"+id(order.path("id"))+"/review?companyId="+f.company();
            var revision=map("version",order.path("version").intValue(),"sampleVersionLock",detail.path("version").intValue(),"result","REVISE",
                    "opinion","Concurrent synthetic revision","reviewDate",today(),"reviewerIds",List.of(actor.id()),"photos",List.of());
            String revisionKey=UUID.randomUUID().toString();parallel20(actor,revisePath,revision,f.company(),revisionKey);
            JsonNode replay=sampleCall(actor,"POST",revisePath,revision,f.company(),revisionKey);assertThat(replay.path("code").intValue()).isZero();
            assertThat(replay.path("data").path("sampleVersion").intValue()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order WHERE sample_id=?",Integer.class,sample)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_review r JOIN dev_sample_order o ON o.id=r.sample_order_id WHERE o.sample_id=?",Integer.class,sample)).isEqualTo(1);
            dispatchInternal(actor,f.company(),sample,actor.id());completeSend(actor,f.company(),sample);JsonNode passed=reviewSample(actor,f.company(),sample,"PASS");
            String code="S2"+suffix().toUpperCase(),path="/api/samples/"+sample+"/convert?companyId="+f.company(),key=UUID.randomUUID().toString();
            var convert=map("version",passed.path("sampleVersionLock").intValue(),"codeMode","MANUAL","code",code,"styleClass","FS","name","One converted style");
            parallel20(actor,path,convert,f.company(),key);JsonNode converted=sampleCall(actor,"POST",path,convert,f.company(),key);
            assertThat(converted.path("code").intValue()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sty_style WHERE code=?",Integer.class,code)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='SAMPLE' AND doc_id=? AND action='convert'",Integer.class,sample)).isEqualTo(1);
        } finally {saveSampleHttp("twenty-revision-and-convert");}
    }

    @Test void longIdsStayStringsAndDeletedDraftRetainsHistoryButCompletedReceiptStillReplaysSafely() throws Exception {
        try {
            Fixture f=fixture();Account actor=sampleActor(f);
            jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('dev_sample','id'),GREATEST((SELECT last_value FROM dev_sample_id_seq),9007199256000000),true)",Long.class);
            JsonNode created=createSample(actor,sampleBody(f,actor));assertThat(created.path("id").isString()).isTrue();long sample=id(created.path("id"));
            assertThat(sample).isGreaterThan(9007199254740991L);
            String path="/api/samples/"+sample+"?companyId="+f.company()+"&version=0",key=UUID.randomUUID().toString();
            JsonNode removed=sampleCall(actor,"DELETE",path,null,f.company(),key);assertThat(removed.path("code").intValue()).isZero();
            JsonNode replay=sampleCall(actor,"DELETE",path,null,f.company(),key);assertThat(replay).isEqualTo(removed);
            assertThat(jdbc.queryForObject("SELECT deleted_token FROM dev_sample WHERE id=?",Long.class,sample)).isEqualTo(sample);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order WHERE sample_id=?",Integer.class,sample)).isEqualTo(1);
            assertThat(sampleCall(actor,"GET","/api/samples/"+sample+"?companyId="+f.company(),null,f.company(),null).path("code").intValue()).isEqualTo(404);
            assertThat(sampleCall(actor,"GET","/api/samples/"+sample+"/logs?companyId="+f.company(),null,f.company(),null).path("code").intValue()).isEqualTo(404);
        } finally {saveSampleHttp("long-id-delete-replay");}
    }

    private void parallel20(Account actor,String path,Object body,long company,String key) throws Exception {
        var ready=new CountDownLatch(20);var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs=new ArrayList<java.util.concurrent.Future<JsonNode>>();
            for(int i=0;i<20;i++)jobs.add(pool.submit(()->{ready.countDown();start.await();return sampleCall(actor,"POST",path,body,company,key);}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            int successful=0;for(var job:jobs){int code=job.get(25,TimeUnit.SECONDS).path("code").intValue();assertThat(code).isIn(0,409);if(code==0)successful++;}
            assertThat(successful).isPositive();
        }
    }
}
