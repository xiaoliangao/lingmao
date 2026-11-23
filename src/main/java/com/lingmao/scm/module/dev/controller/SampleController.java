package com.lingmao.scm.module.dev.controller;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.*;
import com.lingmao.scm.module.dev.dto.*;
import com.lingmao.scm.module.dev.service.SampleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/samples")
@RequiredArgsConstructor
public class SampleController extends IdempotencyReplayGuard {
    private final SampleService samples;
    @GetMapping @PreAuthorize("hasAuthority('dev:sample:list')")
    public R<PageResult<SampleRowVO>> page(@Valid SampleQuery query){return R.ok(samples.page(query));}
    @GetMapping("/counts") @PreAuthorize("hasAuthority('dev:sample:list')")
    public R<Map<String,Long>> counts(@Valid SampleQuery query){return R.ok(samples.counts(query));}
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('dev:sample:list')")
    public R<SampleDetailVO> detail(@PathVariable long id,@RequestParam long companyId){return R.ok(samples.detail(companyId,id));}
    @GetMapping("/{id}/orders") @PreAuthorize("hasAuthority('dev:sample:list')")
    public R<SampleVersionPage> versions(@PathVariable long id,@RequestParam long companyId,
            @RequestParam(required=false) Integer beforeVersion,@RequestParam(defaultValue="20") int size){return R.ok(samples.versions(companyId,id,beforeVersion,size));}
    @GetMapping("/by-style/{styleId}") @PreAuthorize("hasAuthority('dev:sample:list')")
    public R<SampleRef> byStyle(@PathVariable long styleId,@RequestParam long companyId){return R.ok(samples.byStyle(companyId,styleId));}
    @PostMapping @PreAuthorize("hasAuthority('dev:sample:add')")
    @IdempotentCreate("SAMPLE_CREATE") @OperLog(module="样衣",action="新建")
    public R<SampleCreated> create(@Valid @RequestBody SampleCreateReq request){return R.ok(samples.create(request));}
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('dev:sample:edit')")
    @IdempotentCreate(value="SAMPLE_UPDATE", companyArgumentIndex=1) @OperLog(module="样衣",action="保存")
    public R<SampleActionResult> update(@PathVariable long id,@RequestParam long companyId,@Valid @RequestBody SampleUpdateReq request){return R.ok(samples.update(companyId,id,request));}
    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('dev:sample:remove')")
    @IdempotentCreate("SAMPLE_REMOVE") @OperLog(module="样衣",action="删除")
    public R<SampleRemoved> delete(@PathVariable long id,@RequestParam long companyId,@RequestParam int version){return R.ok(samples.delete(companyId,id,version));}
    @PutMapping("/{id}/images") @PreAuthorize("hasAuthority('dev:sample:edit')")
    @IdempotentCreate("SAMPLE_IMAGES") @OperLog(module="样衣",action="图片")
    public R<SampleImagesResult> images(@PathVariable long id,@RequestParam long companyId,@Valid @RequestBody SampleImagesReq request){return R.ok(samples.images(companyId,id,request));}
    @PostMapping("/{id}/stop") @PreAuthorize("hasAuthority('dev:sample:review')")
    @IdempotentCreate("SAMPLE_STOP") @OperLog(module="样衣",action="终止")
    public R<SampleActionResult> stop(@PathVariable long id,@RequestParam long companyId,@Valid @RequestBody SampleReasonReq request){return R.ok(samples.stop(companyId,id,request));}
    @PostMapping("/{id}/reopen") @PreAuthorize("hasAuthority('dev:sample:review')")
    @IdempotentCreate("SAMPLE_REOPEN") @OperLog(module="样衣",action="恢复")
    public R<SampleActionResult> reopen(@PathVariable long id,@RequestParam long companyId,@Valid @RequestBody SampleReasonReq request){return R.ok(samples.reopen(companyId,id,request));}
    @GetMapping("/{id}/convert-preview") @PreAuthorize("hasAuthority('dev:sample:convert')")
    public R<SampleConvertPreview> convertPreview(@PathVariable long id,@RequestParam long companyId,@RequestParam String styleClass){return R.ok(samples.convertPreview(companyId,id,styleClass));}
    @PostMapping("/{id}/convert") @PreAuthorize("hasAuthority('dev:sample:convert')")
    @IdempotentCreate("SAMPLE_CONVERT") @OperLog(module="样衣",action="转大货")
    public R<SampleConvertResult> convert(@PathVariable long id,@RequestParam long companyId,@Valid @RequestBody SampleConvertReq request){return R.ok(samples.convert(companyId,id,request));}
    @GetMapping("/{id}/logs") @PreAuthorize("hasAuthority('dev:sample:list')")
    public R<PageResult<DocLogVO>> logs(@PathVariable long id,@RequestParam long companyId,@Valid PageQuery query){return R.ok(samples.logs(companyId,id,query));}
    @GetMapping("/{id}/references") @PreAuthorize("hasAuthority('dev:sample:list')")
    public R<List<DocRef>> references(@PathVariable long id,@RequestParam long companyId){return R.ok(samples.references(companyId,id));}

    @Override public void authorizeReplay(String operation,Object[] arguments,JsonNode result){
        String permission=switch(operation){
            case "SAMPLE_CREATE"->"add";case "SAMPLE_UPDATE","SAMPLE_IMAGES"->"edit";case "SAMPLE_REMOVE"->"remove";
            case "SAMPLE_STOP","SAMPLE_REOPEN"->"review";case "SAMPLE_CONVERT"->"convert";
            default->throw new IllegalStateException("未知样衣幂等动作");};
        if(result==null||!result.isObject()||!result.hasNonNull("companyId"))throw new IllegalStateException("样衣动作收据缺少主体");
        long id=operation.equals("SAMPLE_CREATE")?result.path("id").asLong():((Number)arguments[0]).longValue();
        if(operation.equals("SAMPLE_UPDATE")){
            samples.authorizeUpdateReplay(((Number)arguments[1]).longValue(),result.path("companyId").asLong(),id);
            return;
        }
        samples.authorizeReplay(permission,result.path("companyId").asLong(),id,operation.equals("SAMPLE_REMOVE"));
    }
}
