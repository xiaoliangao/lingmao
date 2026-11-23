package com.lingmao.scm.security;

import com.lingmao.scm.SqlStatementCapture;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.dev.dto.SampleConvertReq;
import com.lingmao.scm.module.dev.service.SampleService;
import com.lingmao.scm.module.style.dto.StyleCreateReq;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class S2SampleReadModelIT extends S2SampleFixture {
    @Autowired UserService users;
    @Autowired DictDataService dictionaries;
    @Autowired SampleService samples;
    @Autowired TechPackService packs;

    @Test void sizeGroupGrowthAfterCreationCannotMakePackReadsOrStyleConversionReadUnlimitedChildren() throws Exception {
        Fixture f=fixture();Account actor=sampleActor(f);String prefix="Q"+suffix().substring(0,8);
        try {
            long sample=id(createSample(actor,sampleBody(f,actor)).path("id"));long pack=createSamplePack(actor,f.company(),sample);
            dispatchInternal(actor,f.company(),sample,actor.id());completeSend(actor,f.company(),sample);JsonNode passed=reviewSample(actor,f.company(),sample,"PASS");
            jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) SELECT ?,1000+g,?||g FROM generate_series(1,100) g",f.sizeGroup(),prefix);
            authenticate(actor);
            var read=rejectedSql(()->packs.detail(pack));
            assertThat(read.result().getCode()).isEqualTo(400);
            assertSizeQueriesBounded(read);
            var request=new SampleConvertReq(passed.path("sampleVersionLock").intValue(),StyleCreateReq.CodeMode.MANUAL,"S2"+suffix().toUpperCase(),"FS","S2 bounded conversion",null,null);
            var converted=rejectedSql(()->samples.convert(f.company(),sample,request));
            assertThat(converted.result().getCode()).isEqualTo(400);assertSizeQueriesBounded(converted);
            JsonNode httpRead=sampleCall(actor,"GET","/api/tech-packs/"+pack,null,f.company(),null);
            assertThat(httpRead.path("code").intValue()).isEqualTo(400);
            JsonNode httpConvert=sampleCall(actor,"POST","/api/samples/"+sample+"/convert?companyId="+f.company(),request,f.company(),java.util.UUID.randomUUID().toString());
            assertThat(httpConvert.path("code").intValue()).isEqualTo(400);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sty_style WHERE sample_no=(SELECT code FROM dev_sample WHERE id=?)",Integer.class,sample)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM dev_sample WHERE id=?",String.class,sample)).isEqualTo("PASSED");
            evidence("bounded-sample-size-growth.json",map("actualSizeRows",101,"packReadSql",sql(read),"convertSql",sql(converted),"httpRead",httpRead,"httpConvert",httpConvert));
        } finally {jdbc.update("DELETE FROM bas_size_group_item WHERE size_group_id=? AND size_code LIKE ?",f.sizeGroup(),prefix+"%");saveSampleHttp("bounded-size-growth");}
    }

    @Test void enabledPeopleValidationUsesOneRealSelectAndRejectsTheWholeInvalidSet() throws Exception {
        Fixture f=fixture();Account active=sampleActor(f),disabled=sampleActor(f);
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=?",disabled.id());
        var accepted=SqlStatementCapture.capture(()->users.enabledIds(List.of(active.id())));
        assertThat(accepted.result()).containsExactly(active.id());
        assertThat(sql(accepted)).singleElement().satisfies(s->assertThat(s).contains("from sys_user","status"));
        var duplicate=rejectedSql(()->users.enabledIds(List.of(active.id(),active.id())));
        assertThat(duplicate.result().getCode()).isEqualTo(400);assertThat(sql(duplicate)).isEmpty();
        var mixed=rejectedSql(()->users.enabledIds(List.of(active.id(),disabled.id())));
        assertThat(mixed.result().getCode()).isEqualTo(400);assertThat(sql(mixed)).hasSize(1);
        var missing=rejectedSql(()->users.enabledIds(List.of(active.id(),Long.MAX_VALUE)));
        assertThat(missing.result().getCode()).isEqualTo(400);assertThat(sql(missing)).hasSize(1);
        var overflow=rejectedSql(()->users.enabledIds(Collections.nCopies(101,active.id())));
        assertThat(overflow.result().getCode()).isEqualTo(400);assertThat(sql(overflow)).isEmpty();
        evidence("enabled-users-batch-sql.json",map("valid",sql(accepted),"disabledMixed",sql(mixed),"missingMixed",sql(missing),
                "duplicateSqlCount",sql(duplicate).size(),"rawOverflowSqlCount",sql(overflow).size()));
    }

    @Test void enabledNodeBudgetIsEnforcedBySqlLimitAndHttpDispatchRollsBackBeforeCreatingNodes() throws Exception {
        Fixture f=fixture();Account actor=sampleActor(f);String type="s2_samples_"+suffix(),prefix="S2"+suffix();
        try {
            jdbc.update("INSERT INTO sys_dict_type(code,name) VALUES(?,'Synthetic bounded sample templates')",type);
            jdbc.update("INSERT INTO sys_dict_data(type_code,label,value,sort,num_value) SELECT ?,'Synthetic node '||g,'N'||g,g,1 FROM generate_series(1,101) g",type);
            var rejected=rejectedSql(()->dictionaries.enabledForUse(type,100));
            assertThat(rejected.result().getCode()).isEqualTo(400);
            assertThat(sql(rejected)).singleElement().satisfies(s->assertThat(s).contains("order by sort asc,id asc").endsWith("limit 101"));
            jdbc.update("DELETE FROM sys_dict_data WHERE type_code=? AND value='N101'",type);
            var accepted=SqlStatementCapture.capture(()->dictionaries.enabledForUse(type,100));
            assertThat(accepted.result()).hasSize(100);
            assertThat(accepted.result().getFirst().getValue()).isEqualTo("N1");
            assertThat(accepted.result().getLast().getValue()).isEqualTo("N100");
            var invalidMax=rejectedSql(()->dictionaries.enabledForUse(type,101));assertThat(sql(invalidMax)).isEmpty();

            long sample=id(createSample(actor,sampleBody(f,actor)).path("id"));JsonNode detail=sampleDetail(actor,f.company(),sample);
            int original=jdbc.queryForObject("SELECT count(*) FROM sys_dict_data WHERE type_code='sample_node' AND status=1 AND NOT deleted",Integer.class);
            assertThat(original).isBetween(1,100);
            jdbc.update("INSERT INTO sys_dict_data(type_code,label,value,sort,num_value) SELECT 'sample_node','Synthetic extra node',?||g,1000+g,1 FROM generate_series(1,?) g",prefix,101-original);
            JsonNode reply=sampleCall(actor,"POST","/api/sample-orders/"+id(detail.path("currentOrder").path("id"))+"/dispatch?companyId="+f.company(),
                    map("version",0,"sampleVersionLock",0,"makeWay","INTERNAL","makerId",actor.id(),"needDate",today()),f.company(),java.util.UUID.randomUUID().toString());
            assertThat(reply.path("code").intValue()).isEqualTo(400);assertThat(reply.path("msg").stringValue()).contains("超过 100");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_node n JOIN dev_sample_order o ON o.id=n.sample_order_id WHERE o.sample_id=?",Integer.class,sample)).isZero();
            assertThat(jdbc.queryForObject("SELECT version FROM dev_sample WHERE id=?",Integer.class,sample)).isZero();
            evidence("bounded-node-template-sql.json",map("rejectedSql",sql(rejected),"acceptedSql",sql(accepted),"acceptedRows",accepted.result().size(),"dispatchResponse",reply));
        } finally {
            jdbc.update("DELETE FROM sys_dict_data WHERE type_code=?",type);
            jdbc.update("DELETE FROM sys_dict_type WHERE code=?",type);
            jdbc.update("DELETE FROM sys_dict_data WHERE type_code='sample_node' AND value LIKE ?",prefix+"%");
            saveSampleHttp("node-template-limit");
        }
    }
    @Test void homeReviewSortsTheEntireVisibleSetBeforeTwentyPreviewAndKeepsIndependentTotal() throws Exception {
        try {
            Fixture f=fixture();Account all=sampleActor(f);
            Account self=custom(f.company(),"SELF","dev:sample:list","dev:sample:add","dev:sample:make","dev:sample:review");
            List<Long> created=new ArrayList<>();
            for(int i=0;i<25;i++) {
                long sample=id(createSample(all,sampleBody(f,self)).path("id"));created.add(sample);
                dispatchInternal(all,f.company(),sample,all.id());sendOn(all,f.company(),sample,today().minusDays(i));
            }
            List<Long> ordered=new ArrayList<>(created);Collections.reverse(ordered);
            JsonNode first=sampleOk(self,"GET","/api/board/home/todos?companyId="+f.company(),null,f.company());
            JsonNode group=group(first,"SAMPLE_REVIEW");
            assertThat(group.path("total").longValue()).isEqualTo(25);
            assertThat(refIds(group)).containsExactlyElementsOf(ordered.subList(0,20));
            for(JsonNode row:group.path("items")) {
                assertThat(row.path("ref").path("id").isString()).isTrue();
                assertThat(row.path("ref").path("companyId").stringValue()).isEqualTo(Long.toString(f.company()));
            }
            assertCount(first,"SAMPLE_REVIEW",25,f.company());

            // SELF can also see a sample they created for another designer; "mine" narrows by actual designer.
            var delegatedBody=sampleBody(f,all);delegatedBody.remove("estCost");
            long createdForOther=id(createSample(self,delegatedBody).path("id"));
            dispatchInternal(self,f.company(),createdForOther,all.id());sendOn(self,f.company(),createdForOther,today().minusDays(100));
            JsonNode allVisible=sampleOk(self,"GET","/api/board/home/todos?companyId="+f.company()+"&ownerId="+all.id(),null,f.company());
            assertThat(group(allVisible,"SAMPLE_REVIEW").path("total").longValue()).isEqualTo(26);
            assertThat(refIds(group(allVisible,"SAMPLE_REVIEW")).getFirst()).isEqualTo(createdForOther);
            JsonNode mine=sampleOk(self,"GET","/api/board/home/todos?companyId="+f.company()+"&mine=true",null,f.company());
            assertThat(group(mine,"SAMPLE_REVIEW").path("total").longValue()).isEqualTo(25);
            assertThat(refIds(group(mine,"SAMPLE_REVIEW"))).doesNotContain(createdForOther);
            JsonNode counts=sampleOk(self,"GET","/api/samples/counts?companyId="+f.company()+"&status=MAKING",null,f.company());
            assertThat(counts.path("ALL").longValue()).isEqualTo(26);
            assertThat(counts.path("WAIT_REVIEW").longValue()).isEqualTo(26);
            assertThat(counts.path("MAKING").longValue()).isZero();
            JsonNode selected=sampleOk(all,"GET","/api/board/home/todos?companyId="+f.company()+"&ownerId="+self.id(),null,f.company());
            assertThat(group(selected,"SAMPLE_REVIEW").path("total").longValue()).isEqualTo(25);

            Account readOnly=custom(f.company(),"ALL","dev:sample:list");
            JsonNode withoutReview=sampleOk(readOnly,"GET","/api/board/home/todos?companyId="+f.company(),null,f.company());
            assertThat(hasGroup(withoutReview,"SAMPLE_REVIEW")).isFalse();
            assertThat(withoutReview.path("counts").toString()).doesNotContain("SAMPLE_REVIEW");
            JsonNode otherCompany=sampleOk(all,"GET","/api/board/home/todos?companyId="+f.otherCompany(),null,f.otherCompany());
            assertThat(hasGroup(otherCompany,"SAMPLE_REVIEW")).isFalse();
        } finally {saveSampleHttp("home-review-preview-total-scope");}
    }

    @Test void sampleOnlyKpiIsRealAndNullableAndLateOwnerFilterUsesActualCurrentNode() throws Exception {
        try {
            Fixture f=fixture();Account editor=sampleActor(f);
            Account maker=custom(f.company(),"SELF","dev:sample:list");
            long own=id(createSample(editor,sampleBody(f,editor)).path("id"));
            long other=id(createSample(editor,sampleBody(f,editor)).path("id"));
            dispatchOn(editor,f.company(),own,maker.id(),today().minusDays(2));
            dispatchOn(editor,f.company(),other,editor.id(),today().minusDays(5));
            JsonNode kpi=sampleOk(maker,"GET","/api/board/home/kpi?companyId="+f.company(),null,f.company()).path("dev");
            assertThat(kpi.path("sampleMakingCount").longValue()).isEqualTo(1);
            assertThat(kpi.path("companyId").stringValue()).isEqualTo(Long.toString(f.company()));
            assertThat(kpi.path("styleCount").isNull()).isTrue();
            assertThat(kpi.path("packDraftCount").isNull()).isTrue();
            JsonNode noCompany=sampleOk(maker,"GET","/api/board/home/kpi",null,null).path("dev");
            assertThat(noCompany.path("sampleMakingCount").isNull()).isTrue();
            assertThat(noCompany.path("companyId").isNull()).isTrue();
            JsonNode late=sampleOk(maker,"GET","/api/board/home/todos?companyId="+f.company()+"&mine=true",null,f.company());
            assertThat(refIds(group(late,"SAMPLE_LATE"))).containsExactly(own);
            assertCount(late,"SAMPLE_LATE",1,f.company());
            JsonNode allLate=sampleOk(editor,"GET","/api/board/home/todos?companyId="+f.company(),null,f.company());
            assertThat(refIds(group(allLate,"SAMPLE_LATE"))).containsExactly(other,own);
            assertThat(group(allLate,"SAMPLE_LATE").path("total").longValue()).isEqualTo(2);
            JsonNode filtered=sampleOk(editor,"GET","/api/board/home/todos?companyId="+f.company()+"&ownerId="+maker.id(),null,f.company());
            assertThat(refIds(group(filtered,"SAMPLE_LATE"))).containsExactly(own);
            Account none=custom(f.company(),"ALL","biz:tag:list");
            assertThat(sampleOk(none,"GET","/api/board/home/kpi?companyId="+f.company(),null,f.company()).path("dev").isNull()).isTrue();
        } finally {saveSampleHttp("home-kpi-null-and-late-owner");}
    }

    @Test void fiveHundredCharacterReasonsAndThousandCharacterPassOpinionsKeepTheirActualText() throws Exception {
        try {
            Fixture f=fixture();Account actor=sampleActor(f);long sample=id(createSample(actor,sampleBody(f,actor)).path("id"));
            String reason="因".repeat(500);
            JsonNode stopped=sampleOk(actor,"POST","/api/samples/"+sample+"/stop?companyId="+f.company(),map("version",0,"reason",reason),f.company());
            assertThat(jdbc.queryForObject("SELECT remark FROM biz_doc_log WHERE doc_type='SAMPLE' AND doc_id=? AND action='stop' ORDER BY id DESC LIMIT 1",String.class,sample)).isEqualTo(reason);
            sampleOk(actor,"POST","/api/samples/"+sample+"/reopen?companyId="+f.company(),map("version",stopped.path("sampleVersionLock").intValue(),"reason",reason),f.company());
            assertThat(sampleDetail(actor,f.company(),sample).path("currentOrder").path("reason").stringValue()).isEqualTo(reason);
            dispatchInternal(actor,f.company(),sample,actor.id());completeSend(actor,f.company(),sample);
            JsonNode detail=sampleDetail(actor,f.company(),sample),order=detail.path("currentOrder");String opinion="评".repeat(1000);
            sampleOk(actor,"POST","/api/sample-orders/"+id(order.path("id"))+"/review?companyId="+f.company(),
                    map("version",order.path("version").intValue(),"sampleVersionLock",detail.path("version").intValue(),"result","PASS",
                            "opinion",opinion,"reviewDate",today(),"reviewerIds",List.of(actor.id()),"photos",List.of()),f.company());
            JsonNode actualReview=sampleDetail(actor,f.company(),sample).path("currentOrder").path("review");
            assertThat(actualReview.path("opinion").stringValue()).isEqualTo(opinion);
            String timeline=jdbc.queryForObject("SELECT remark FROM biz_doc_log WHERE doc_type='SAMPLE' AND doc_id=? AND action='review' ORDER BY id DESC LIMIT 1",String.class,sample);
            assertThat(timeline).contains(actualReview.path("id").stringValue()).hasSizeLessThanOrEqualTo(500);
        } finally {saveSampleHttp("reason-opinion-real-storage-limits");}
    }

    @Test void revisionOpinionOverFiveHundredIsRejectedBeforeAnyWriteAndExactLimitIsPreserved() throws Exception {
        try {
            Fixture f=fixture();Account actor=sampleActor(f);
            long sample=id(createSample(actor,sampleBody(f,actor)).path("id"));
            dispatchInternal(actor,f.company(),sample,actor.id());completeSend(actor,f.company(),sample);
            JsonNode before=sampleDetail(actor,f.company(),sample),order=before.path("currentOrder");
            long orderId=id(order.path("id"));
            int events=jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='SAMPLE' AND doc_id=?",Integer.class,sample);
            String path="/api/sample-orders/"+orderId+"/review?companyId="+f.company();
            String key=java.util.UUID.randomUUID().toString();
            var body=map("version",order.path("version").intValue(),"sampleVersionLock",before.path("version").intValue(),
                    "result","REVISE","opinion","改".repeat(501),"reviewDate",today(),
                    "reviewerIds",List.of(actor.id()),"photos",List.of());
            JsonNode rejected=sampleCall(actor,"POST",path,body,f.company(),key);
            assertThat(rejected.path("code").intValue()).isEqualTo(400);
            assertThat(rejected.path("msg").stringValue()).contains("500");
            JsonNode unchanged=sampleDetail(actor,f.company(),sample);
            assertThat(unchanged.path("version")).isEqualTo(before.path("version"));
            assertThat(unchanged.path("currentOrder").path("version")).isEqualTo(order.path("version"));
            assertThat(unchanged.path("currentOrder").path("status").stringValue()).isEqualTo("WAIT_REVIEW");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_review WHERE sample_order_id=?",Integer.class,orderId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order WHERE sample_id=?",Integer.class,sample)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='SAMPLE' AND doc_id=?",Integer.class,sample)).isEqualTo(events);
            // A failed write leaves no completed receipt; the same key can submit the corrected original intent.
            String exact="改".repeat(500);body.put("opinion",exact);
            JsonNode accepted=sampleCall(actor,"POST",path,body,f.company(),key);
            assertThat(accepted.path("code").intValue()).isZero();
            assertThat(accepted.path("data").path("sampleVersion").intValue()).isEqualTo(2);
            assertThat(sampleDetail(actor,f.company(),sample).path("currentOrder").path("reason").stringValue()).isEqualTo(exact);
            assertThat(jdbc.queryForObject("SELECT opinion FROM dev_sample_review WHERE sample_order_id=?",String.class,orderId)).isEqualTo(exact);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_review WHERE sample_order_id=?",Integer.class,orderId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order WHERE sample_id=?",Integer.class,sample)).isEqualTo(2);
        } finally {saveSampleHttp("revision-opinion-explicit-limit");}
    }

    private void sendOn(Account actor,long company,long sample,LocalDate date) throws Exception {
        JsonNode detail=sampleDetail(actor,company,sample),order=detail.path("currentOrder");JsonNode send=null;
        for(JsonNode node:order.path("nodes"))if(node.path("nodeCode").stringValue().equals("SEND"))send=node;
        assertThat(send).isNotNull();
        sampleOk(actor,"POST","/api/sample-orders/"+id(order.path("id"))+"/nodes/"+id(send.path("id"))+"/done?companyId="+company,
                map("version",order.path("version").intValue(),"sampleVersionLock",detail.path("version").intValue(),"doneDate",date),company);
    }
    private void dispatchOn(Account actor,long company,long sample,long maker,LocalDate date) throws Exception {
        JsonNode detail=sampleDetail(actor,company,sample),order=detail.path("currentOrder");
        sampleOk(actor,"POST","/api/sample-orders/"+id(order.path("id"))+"/dispatch?companyId="+company,
                map("version",order.path("version").intValue(),"sampleVersionLock",detail.path("version").intValue(),"makeWay","INTERNAL","makerId",maker,"needDate",date),company);
    }
    private static boolean hasGroup(JsonNode response,String key){for(JsonNode group:response.path("groups"))if(key.equals(group.path("key").stringValue()))return true;return false;}
    private static JsonNode group(JsonNode response,String key){for(JsonNode group:response.path("groups"))if(key.equals(group.path("key").stringValue()))return group;throw new AssertionError("Missing actual group "+key+": "+response);}
    private static List<Long> refIds(JsonNode group){List<Long> ids=new ArrayList<>();for(JsonNode item:group.path("items"))ids.add(id(item.path("ref").path("id")));return ids;}
    private static void assertCount(JsonNode response,String key,long total,long company){for(JsonNode count:response.path("counts"))if(key.equals(count.path("key").stringValue())){assertThat(count.path("count").longValue()).isEqualTo(total);assertThat(count.path("companyId").stringValue()).isEqualTo(Long.toString(company));return;}throw new AssertionError("Missing count "+key);}
    private static SqlStatementCapture.Captured<BizException> rejectedSql(Runnable call){return SqlStatementCapture.capture(()->{try{call.run();}catch(BizException ex){return ex;}throw new AssertionError("Expected complete validation rejection");});}
    private static List<String> sql(SqlStatementCapture.Captured<?> captured){return captured.statements().stream().map(s->s.getSql().replaceAll("\\s+"," ").strip().toLowerCase(java.util.Locale.ROOT)).toList();}
    private static void assertSizeQueriesBounded(SqlStatementCapture.Captured<?> captured){List<String> queries=sql(captured).stream().filter(s->s.contains("from bas_size_group_item")).toList();assertThat(queries).isNotEmpty();queries.forEach(s->assertThat(s).endsWith("limit 101"));}
    private void evidence(String name,Object value) throws Exception {Path p=Path.of("target/a2-sample/read-models",name);Files.createDirectories(p.getParent());Files.writeString(p,json.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
}
