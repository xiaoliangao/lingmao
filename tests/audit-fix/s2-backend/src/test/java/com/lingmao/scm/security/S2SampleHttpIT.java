package com.lingmao.scm.security;

import com.lingmao.scm.TestColors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class S2SampleHttpIT extends S2SampleFixture {
    @Test void firstRevisionDispatchSendReviewAndConversionProduceRealStyleSkusAndRepricedProdPack() throws Exception {
        try {
            Fixture f=fixture();Account actor=sampleActor(f);var body=sampleBody(f,actor);
            long secondColor=TestColors.insert(jdbc,"S2 second color "+suffix()),thirdColor=TestColors.insert(jdbc,"S2 third color "+suffix());
            body.put("colorIds",List.of(f.color(),secondColor,thirdColor));
            jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES(?,2,'S'),(?,3,'L'),(?,4,'XL'),(?,5,'XXL')",
                    f.sizeGroup(),f.sizeGroup(),f.sizeGroup(),f.sizeGroup());
            JsonNode created=createSample(actor,body);long sample=id(created.path("id"));
            assertThat(created.path("id").isString()).isTrue();assertThat(created.path("currentOrderId").isString()).isTrue();
            JsonNode initial=sampleDetail(actor,f.company(),sample);
            assertThat(initial.path("estCost").isString()).isTrue();assertThat(initial.path("estCost").stringValue()).isEqualTo("12.3456");
            assertThat(initial.path("currentOrder").path("sampleVersion").intValue()).isEqualTo(1);
            assertThat(initial.path("currentOrder").path("colors").size()).isEqualTo(1);
            long pack=createSamplePack(actor,f.company(),sample);int packVersion=saveSamplePack(actor,f,pack,"first development", "11.0000");
            sampleOk(actor,"POST","/api/tech-packs/"+pack+"/publish",Map.of("versionLock",packVersion),f.company());
            long effective=id(sampleOk(actor,"POST","/api/tech-packs/"+pack+"/revise",null,f.company()));
            int secondPackVersion=saveSamplePack(actor,f,effective,"second development", "11.0000");
            sampleOk(actor,"POST","/api/tech-packs/"+effective+"/publish",Map.of("versionLock",secondPackVersion),f.company());
            long draft=id(sampleOk(actor,"POST","/api/tech-packs/"+effective+"/revise",null,f.company()));
            JsonNode dispatched=dispatchInternal(actor,f.company(),sample,actor.id());
            assertThat(dispatched.path("orderStatus").stringValue()).isEqualTo("MAKING");
            JsonNode kpi=sampleOk(actor,"GET","/api/board/home/kpi?companyId="+f.company(),null,f.company());
            assertThat(kpi.path("dev").path("sampleMakingCount").longValue()).isEqualTo(1);
            assertThat(kpi.path("dev").path("companyId").isString()).isTrue();
            JsonNode sent=completeSend(actor,f.company(),sample);
            assertThat(sent.path("warning").stringValue()).contains("前面还有节点");
            assertThat(sent.path("orderStatus").stringValue()).isEqualTo("WAIT_REVIEW");
            long firstOrder=id(sent.path("orderId"));
            JsonNode revised=reviewSample(actor,f.company(),sample,"REVISE");
            assertThat(revised.path("sampleVersion").intValue()).isEqualTo(2);
            assertThat(revised.path("orderStatus").stringValue()).isEqualTo("WAIT_ASSIGN");
            assertThat(id(revised.path("orderId"))).isNotEqualTo(firstOrder);
            JsonNode history=sampleOk(actor,"GET","/api/samples/"+sample+"/orders?companyId="+f.company()+"&size=1",null,f.company());
            assertThat(history.path("list").size()).isEqualTo(1);assertThat(history.path("hasMore").booleanValue()).isTrue();
            JsonNode older=sampleOk(actor,"GET","/api/samples/"+sample+"/orders?companyId="+f.company()+"&size=1&beforeVersion="+history.path("nextBeforeVersion").intValue(),null,f.company());
            assertThat(id(older.path("list").get(0).path("id"))).isEqualTo(firstOrder);
            assertThat(older.path("list").get(0).path("review").path("result").stringValue()).isEqualTo("REVISE");
            dispatchInternal(actor,f.company(),sample,actor.id());completeSend(actor,f.company(),sample);
            JsonNode passed=reviewSample(actor,f.company(),sample,"PASS");
            assertThat(passed.path("sampleStatus").stringValue()).isEqualTo("PASSED");
            jdbc.update("UPDATE bas_material_sku SET price=22.1234 WHERE id=?",f.materialSku());
            String key=UUID.randomUUID().toString();var convert=map("version",passed.path("sampleVersionLock").intValue(),
                    "codeMode","MANUAL","code","S2"+suffix().toUpperCase(),"styleClass","FS","name","S2 converted style");
            JsonNode reply=sampleCall(actor,"POST","/api/samples/"+sample+"/convert?companyId="+f.company(),convert,f.company(),key);
            assertThat(reply.path("code").intValue()).isZero();JsonNode converted=reply.path("data");long style=id(converted.path("styleId"));
            assertThat(converted.path("styleId").isString()).isTrue();assertThat(converted.path("skuCount").intValue()).isEqualTo(15);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sty_style_sku WHERE style_id=?",Integer.class,style)).isEqualTo(15);
            assertThat(jdbc.queryForObject("SELECT sample_no FROM sty_style WHERE id=?",String.class,style)).isEqualTo(created.path("code").stringValue());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tp_tech_pack WHERE id IN (?,?,?) AND style_id=? AND sample_id=?",Integer.class,pack,effective,draft,style,sample)).isEqualTo(3);
            long prod=id(converted.path("prodPackId"));
            assertThat(jdbc.queryForObject("SELECT stage||':'||status||':'||version FROM tp_tech_pack WHERE id=?",String.class,prod)).isEqualTo("PROD:DRAFT:1");
            assertThat(jdbc.queryForObject("SELECT source_pack_id FROM tp_tech_pack WHERE id=?",Long.class,prod)).isEqualTo(effective);
            assertThat(jdbc.queryForObject("SELECT version FROM tp_tech_pack WHERE id=?",Integer.class,effective)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT ref_price FROM tp_bom_item WHERE tech_pack_id=?",BigDecimal.class,prod)).isEqualByComparingTo("22.1234");
            assertThat(jdbc.queryForObject("SELECT ref_price FROM tp_bom_item WHERE tech_pack_id=?",BigDecimal.class,pack)).isEqualByComparingTo("11.0000");
            JsonNode replay=sampleCall(actor,"POST","/api/samples/"+sample+"/convert?companyId="+f.company(),convert,f.company(),key);
            assertThat(replay.path("code").intValue()).isZero();assertThat(replay.path("data")).isEqualTo(converted);
            assertThat(id(sampleOk(actor,"GET","/api/samples/by-style/"+style+"?companyId="+f.company(),null,f.company()).path("id"))).isEqualTo(sample);
            Account packOnly=custom(f.company(),"SELF","techpack:pack:list");
            assertThat(id(sampleOk(packOnly,"GET","/api/tech-packs/"+pack,null,null).path("styleId"))).isEqualTo(style);
        } finally {saveSampleHttp("full-chain");}
    }

    @Test void replacingAllColorsAndChangingCompanySizeGroupAndColorInOneRequestKeepsSelectionsConsistent() throws Exception {
        try {
            Fixture f=fixture();Account actor=sampleActor(f);var body=sampleBody(f,actor);long sample=id(createSample(actor,body).path("id"));
            long fresh=TestColors.insert(jdbc,"S2 all-new "+suffix());var first=editable(body);first.put("version",0);first.put("colorIds",List.of(fresh));
            JsonNode a=sampleOk(actor,"PUT","/api/samples/"+sample+"?companyId="+f.company(),first,f.company());
            assertThat(a.path("sampleVersionLock").intValue()).isEqualTo(1);assertThat(a.path("version").intValue()).isEqualTo(1);
            JsonNode detail=sampleDetail(actor,f.company(),sample);assertThat(id(detail.path("currentOrder").path("colors").get(0).path("id"))).isEqualTo(fresh);
            long newer=TestColors.insert(jdbc,"S2 moved-new "+suffix());
            long sizes=jdbc.queryForObject("INSERT INTO bas_size_group(name,base_size) VALUES(?,'XL') RETURNING id",Long.class,"S2 moved size "+suffix());
            jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES(?,1,'XL'),(?,2,'XXL')",sizes,sizes);
            var moved=editable(body);moved.put("version",1);moved.put("companyId",f.otherCompany());moved.put("sizeGroupId",sizes);moved.put("colorIds",List.of(newer));
            JsonNode b=sampleOk(actor,"PUT","/api/samples/"+sample+"?companyId="+f.company(),moved,f.company());
            assertThat(b.path("sampleVersionLock").intValue()).isEqualTo(2);assertThat(b.path("version").intValue()).isEqualTo(2);
            assertThat(id(b.path("companyId"))).isEqualTo(f.otherCompany());
            JsonNode after=sampleDetail(actor,f.otherCompany(),sample);
            assertThat(after.path("currentOrder").path("sampleVersion").intValue()).isEqualTo(1);
            assertThat(after.path("currentOrder").path("sizeCodes").get(0).stringValue()).isEqualTo("XL");
            assertThat(id(after.path("currentOrder").path("colors").get(0).path("id"))).isEqualTo(newer);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order WHERE sample_id=?",Integer.class,sample)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order_color WHERE sample_order_id=? AND company_id=?",Integer.class,id(b.path("orderId")),f.otherCompany())).isEqualTo(1);
            assertThat(sampleCall(actor,"GET","/api/samples/"+sample+"?companyId="+f.company(),null,f.company(),null).path("code").intValue()).isEqualTo(404);
        } finally {saveSampleHttp("replace-colors-company-group");}
    }

    @Test void companyMovingUpdateReplaysUnderItsOriginalCompanyAndRechecksTheCurrentOwner() throws Exception {
        try {
            Fixture f=fixture();Account admin=sampleActor(f);
            Account mover=custom(f.company(),"SELF","dev:sample:list","dev:sample:edit","techpack:cost:query");
            var body=sampleBody(f,mover);body.put("patternMakerId",admin.id());
            long sample=id(createSample(admin,body).path("id"));
            var update=editable(body);update.put("version",0);update.put("companyId",f.otherCompany());
            String path="/api/samples/"+sample+"?companyId="+f.company(),key=UUID.randomUUID().toString();
            JsonNode first=sampleCall(mover,"PUT",path,update,f.company(),key);
            assertThat(first.path("code").intValue()).as("the request authorizes against the real original company").isZero();
            JsonNode receipt=first.path("data");
            assertThat(id(receipt.path("companyId"))).isEqualTo(f.otherCompany());
            assertThat(receipt.path("sampleVersionLock").intValue()).isEqualTo(1);
            assertThat(sampleCall(mover,"PUT",path,update,f.company(),key).path("data")).isEqualTo(receipt);
            assertThat(sampleDetail(mover,f.otherCompany(),sample).path("version").intValue()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='SAMPLE' AND doc_id=? AND action='update'",Integer.class,sample)).isEqualTo(1);
            var altered=new LinkedHashMap<>(update);altered.put("name","Changed replay body");
            assertThat(sampleCall(mover,"PUT",path,altered,f.company(),key).path("code").intValue()).isEqualTo(1002);
            assertThat(sampleCall(mover,"PUT",path,update,f.otherCompany(),key).path("code").intValue()).isEqualTo(409);
            assertThat(sampleCall(mover,"GET",path,null,f.company(),null).path("code").intValue()).isEqualTo(404);

            // This actor was never the creator or current maker: the real owner edit removes the last SELF basis.
            var reassigned=new LinkedHashMap<>(update);reassigned.put("version",1);reassigned.put("designerId",admin.id());
            sampleOk(admin,"PUT","/api/samples/"+sample+"?companyId="+f.otherCompany(),reassigned,f.otherCompany());
            assertThat(sampleCall(mover,"PUT",path,update,f.company(),key).path("code").intValue()).isEqualTo(404);
            jdbc.update("DELETE FROM sys_role_menu WHERE role_id=? AND menu_id IN (SELECT id FROM sys_menu WHERE perms='dev:sample:edit')",mover.role());
            assertThat(sampleCall(mover,"PUT",path,update,f.company(),key).path("code").intValue()).isEqualTo(403);
            assertThat(jdbc.queryForObject("SELECT version FROM dev_sample WHERE id=?",Integer.class,sample)).isEqualTo(2);
        } finally {saveSampleHttp("company-update-replay-owner");}
    }

    @Test void revisionAndReopenKeepOldColorSnapshotsAndWarnForPartialOrCompleteRemoval() throws Exception {
        try {
            Fixture f=fixture();Account actor=sampleActor(f);long b=TestColors.insert(jdbc,"S2 B "+suffix()),c=TestColors.insert(jdbc,"S2 C "+suffix());
            var body=sampleBody(f,actor);body.put("colorIds",List.of(f.color(),b));long sample=id(createSample(actor,body).path("id"));
            JsonNode d=sampleDetail(actor,f.company(),sample);JsonNode o=d.path("currentOrder");
            var order=map("version",o.path("version").intValue(),"sampleVersionLock",d.path("version").intValue(),"sampleType","FIRST","qty",3,
                    "colorIds",List.of(f.color(),b),"sizeCodes",List.of("M"),"needDate",today().plusDays(10),"makeWay","INTERNAL","makerId",actor.id());
            sampleOk(actor,"PUT","/api/sample-orders/"+id(o.path("id"))+"?companyId="+f.company(),order,f.company());
            dispatchInternal(actor,f.company(),sample,actor.id());completeSend(actor,f.company(),sample);
            d=sampleDetail(actor,f.company(),sample);long oldOrder=id(d.path("currentOrder").path("id"));
            var removeFirst=editable(body);removeFirst.put("version",d.path("version").intValue());removeFirst.put("colorIds",List.of(b,c));
            sampleOk(actor,"PUT","/api/samples/"+sample+"?companyId="+f.company(),removeFirst,f.company());
            JsonNode revised=reviewSample(actor,f.company(),sample,"REVISE");assertThat(revised.path("warning").stringValue()).contains(f.colorCode());
            d=sampleDetail(actor,f.company(),sample);assertThat(d.path("currentOrder").path("colors").size()).isEqualTo(1);
            assertThat(id(d.path("currentOrder").path("colors").get(0).path("id"))).isEqualTo(b);
            assertThat(jdbc.queryForList("SELECT color_id FROM dev_sample_order_color WHERE sample_order_id=? ORDER BY seq",Long.class,oldOrder)).containsExactly(f.color(),b);
            dispatchInternal(actor,f.company(),sample,actor.id());d=sampleDetail(actor,f.company(),sample);
            var removeAll=editable(body);removeAll.put("version",d.path("version").intValue());removeAll.put("colorIds",List.of(c));
            sampleOk(actor,"PUT","/api/samples/"+sample+"?companyId="+f.company(),removeAll,f.company());
            d=sampleDetail(actor,f.company(),sample);JsonNode stopped=sampleOk(actor,"POST","/api/samples/"+sample+"/stop?companyId="+f.company(),
                    map("version",d.path("version").intValue(),"reason","Synthetic pause"),f.company());
            JsonNode reopened=sampleOk(actor,"POST","/api/samples/"+sample+"/reopen?companyId="+f.company(),
                    map("version",stopped.path("sampleVersionLock").intValue(),"reason","Synthetic reopen"),f.company());
            assertThat(reopened.path("sampleVersion").intValue()).isEqualTo(3);assertThat(reopened.path("warning").stringValue()).contains("第一色");
            d=sampleDetail(actor,f.company(),sample);assertThat(id(d.path("currentOrder").path("colors").get(0).path("id"))).isEqualTo(c);
            assertThat(d.path("currentOrder").path("qty").intValue()).isEqualTo(3);
            assertThat(jdbc.queryForList("SELECT color_id FROM dev_sample_order_color WHERE sample_order_id=? ORDER BY seq",Long.class,oldOrder)).containsExactly(f.color(),b);
        } finally {saveSampleHttp("revision-color-history");}
    }

    @Test void twentyConcurrentCreateRequestsUseOneReceiptAndReplayRechecksCurrentPermissionAndCompany() throws Exception {
        try {
            Fixture f=fixture();Account actor=sampleActor(f);var body=sampleBody(f,actor);String key=UUID.randomUUID().toString();
            var ready=new CountDownLatch(20);var start=new CountDownLatch(1);var replies=new ArrayList<JsonNode>();
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var futures=new ArrayList<java.util.concurrent.Future<JsonNode>>();
                for(int i=0;i<20;i++)futures.add(pool.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new AssertionError("start gate");
                    return sampleCall(actor,"POST","/api/samples",body,f.company(),key);}));
                assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
                for(var future:futures)replies.add(future.get(25,TimeUnit.SECONDS));
            }
            assertThat(replies).allSatisfy(reply->assertThat(reply.path("code").intValue()).isIn(0,409));
            assertThat(replies.stream().filter(r->r.path("code").intValue()==0)).isNotEmpty();
            JsonNode replay=sampleCall(actor,"POST","/api/samples",body,f.company(),key);assertThat(replay.path("code").intValue()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample WHERE company_id=? AND name=?",Integer.class,f.company(),body.get("name"))).isEqualTo(1);
            long sample=id(replay.path("data").path("id"));assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_sample_order WHERE sample_id=?",Integer.class,sample)).isEqualTo(1);
            var different=new LinkedHashMap<>(body);different.put("name","S2 changed body");
            assertThat(sampleCall(actor,"POST","/api/samples",different,f.company(),key).path("code").intValue()).isEqualTo(1002);
            assertThat(sampleCall(actor,"POST","/api/samples",body,f.otherCompany(),key).path("code").intValue()).isEqualTo(409);
            jdbc.update("DELETE FROM sys_role_menu WHERE role_id=? AND menu_id IN (SELECT id FROM sys_menu WHERE perms='dev:sample:add')",actor.role());
            assertThat(sampleCall(actor,"POST","/api/samples",body,f.company(),key).path("code").intValue()).isEqualTo(403);
        } finally {saveSampleHttp("twenty-idempotent-create");}
    }

    @Test void currentScopeCompanyMissingHeadersAndSensitiveColumnsAreEnforcedForSampleAndPack() throws Exception {
        try {
            Fixture f=fixture();Account editor=sampleActor(f);Account owner=custom(f.company(),"SELF","dev:sample:list","techpack:pack:list");
            Account other=custom(f.company(),"SELF","dev:sample:list","techpack:pack:list");
            var body=sampleBody(f,editor);body.put("designerId",owner.id());long sample=id(createSample(editor,body).path("id"));long pack=createSamplePack(editor,f.company(),sample);
            JsonNode readable=sampleDetail(owner,f.company(),sample);
            assertThat(readable.has("estCost")).isFalse();assertThat(readable.path("estTagPrice").stringValue()).isEqualTo("129.90");
            assertThat(sampleCall(other,"GET","/api/samples/"+sample+"?companyId="+f.company(),null,f.company(),null).path("code").intValue()).isEqualTo(404);
            assertThat(sampleCall(other,"GET","/api/tech-packs/"+pack,null,f.company(),null).path("code").intValue()).isEqualTo(404);
            assertThat(sampleCall(owner,"GET","/api/tech-packs/"+pack,null,null,null).path("code").intValue()).isEqualTo(400);
            assertThat(sampleCall(owner,"GET","/api/tech-packs/"+pack,null,f.otherCompany(),null).path("code").intValue()).isEqualTo(403);
            JsonNode packJson=sampleOk(owner,"GET","/api/tech-packs/"+pack,null,f.company());
            assertThat(packJson.has("unitCost")).isFalse();assertThat(packJson.path("companyId").isString()).isTrue();
            var injection=new LinkedHashMap<>(body);injection.put("status","CONVERTED");
            assertThat(sampleCall(editor,"POST","/api/samples",injection,f.company(),UUID.randomUUID().toString()).path("code").intValue()).isEqualTo(400);
            var stolen=editable(body);stolen.put("version",readable.path("version").intValue());stolen.put("designerId",editor.id());
            sampleOk(editor,"PUT","/api/samples/"+sample+"?companyId="+f.company(),stolen,f.company());
            assertThat(sampleCall(owner,"GET","/api/tech-packs/"+pack,null,f.company(),null).path("code").intValue()).isEqualTo(404);
        } finally {saveSampleHttp("owner-columns-company");}
    }

    static Map<String,Object> editable(Map<String,Object> create) {
        var value=new LinkedHashMap<>(create);value.remove("needDate");value.remove("images");return value;
    }
}
