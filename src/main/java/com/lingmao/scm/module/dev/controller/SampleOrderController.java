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
@RequestMapping("/api/sample-orders")
@RequiredArgsConstructor
public class SampleOrderController extends IdempotencyReplayGuard {
    private final SampleService samples;
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('dev:sample:edit')")
    @IdempotentCreate("SAMPLE_ORDER_UPDATE") @OperLog(module="打样单",action="保存")
    public R<SampleActionResult> save(@PathVariable long id,@RequestParam long companyId,@Valid @RequestBody SampleOrderReq request){return R.ok(samples.saveOrder(companyId,id,request));}
    @PostMapping("/{id}/dispatch") @PreAuthorize("hasAuthority('dev:sample:make')")
    @IdempotentCreate("SAMPLE_DISPATCH") @OperLog(module="打样单",action="派单")
    public R<SampleActionResult> dispatch(@PathVariable long id,@RequestParam long companyId,@Valid @RequestBody SampleDispatchReq request){return R.ok(samples.dispatch(companyId,id,request));}
    @GetMapping("/{id}/dispatch-preview") @PreAuthorize("hasAuthority('dev:sample:make')")
    public R<SampleDispatchPreview> preview(@PathVariable long id,@RequestParam long companyId,@RequestParam LocalDate needDate){return R.ok(samples.dispatchPreview(companyId,id,needDate));}
    @PutMapping("/{id}/nodes") @PreAuthorize("hasAuthority('dev:sample:make')")
    @IdempotentCreate("SAMPLE_NODE_PLANS") @OperLog(module="打样单",action="节点计划")
    public R<SampleActionResult> plans(@PathVariable long id,@RequestParam long companyId,@Valid @RequestBody SampleNodePlansReq request){return R.ok(samples.plans(companyId,id,request));}
    @PostMapping("/{id}/nodes/{nodeId}/done") @PreAuthorize("hasAuthority('dev:sample:make')")
    @IdempotentCreate("SAMPLE_NODE_DONE") @OperLog(module="打样单",action="节点完成")
    public R<SampleActionResult> done(@PathVariable long id,@RequestParam long companyId,@PathVariable long nodeId,@Valid @RequestBody SampleNodeDoneReq request){return R.ok(samples.done(companyId,id,nodeId,request));}
    @DeleteMapping("/{id}/nodes/{nodeId}/done") @PreAuthorize("hasAuthority('dev:sample:make')")
    @IdempotentCreate("SAMPLE_NODE_UNDO") @OperLog(module="打样单",action="撤回节点")
    public R<SampleActionResult> undo(@PathVariable long id,@RequestParam long companyId,@PathVariable long nodeId,
            @RequestParam int version,@RequestParam int sampleVersionLock){return R.ok(samples.undoDone(companyId,id,nodeId,version,sampleVersionLock));}
    @PostMapping("/{id}/review") @PreAuthorize("hasAuthority('dev:sample:review')")
    @IdempotentCreate("SAMPLE_REVIEW") @OperLog(module="打样单",action="评审")
    public R<SampleActionResult> review(@PathVariable long id,@RequestParam long companyId,@Valid @RequestBody SampleReviewReq request){return R.ok(samples.review(companyId,id,request));}
    @GetMapping("/{id}/attachments") @PreAuthorize("hasAuthority('dev:sample:list')")
    public R<List<AttachmentVO>> files(@PathVariable long id,@RequestParam long companyId){return R.ok(samples.orderFiles(companyId,id));}
    @PutMapping("/{id}/attachments") @PreAuthorize("hasAuthority('dev:sample:edit')")
    @IdempotentCreate("SAMPLE_ORDER_FILES") @OperLog(module="打样单",action="附件")
    public R<SampleOrderFilesResult> files(@PathVariable long id,@RequestParam long companyId,@Valid @RequestBody SampleOrderFilesReq request){return R.ok(samples.orderFiles(companyId,id,request));}
    @Override public void authorizeReplay(String operation,Object[] arguments,JsonNode result){
        String permission=switch(operation){
            case "SAMPLE_ORDER_UPDATE","SAMPLE_ORDER_FILES"->"edit";
            case "SAMPLE_DISPATCH","SAMPLE_NODE_PLANS","SAMPLE_NODE_DONE","SAMPLE_NODE_UNDO"->"make";
            case "SAMPLE_REVIEW"->"review";default->throw new IllegalStateException("未知打样幂等动作");};
        samples.authorizeOrderReplay(permission,((Number)arguments[1]).longValue(),((Number)arguments[0]).longValue());
    }
}
