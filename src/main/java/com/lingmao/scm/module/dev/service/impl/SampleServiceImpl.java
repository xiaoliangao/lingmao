package com.lingmao.scm.module.dev.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.biz.dto.*;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.service.*;
import com.lingmao.scm.module.dev.core.*;
import com.lingmao.scm.module.dev.dto.*;
import com.lingmao.scm.module.dev.entity.*;
import com.lingmao.scm.module.dev.repository.SampleRepository;
import com.lingmao.scm.module.dev.service.SampleService;
import com.lingmao.scm.module.style.dto.StyleCreateReq;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 所有领域动作与编号、图片、评审、操作记录同事务；不在事务内执行文件解析或外部 HTTP。 */
@Service
@RequiredArgsConstructor
public class SampleServiceImpl implements SampleService {
    private final com.lingmao.scm.module.biz.service.TagFilterService tagFilters;
    private final SampleRepository repository;
    private final SampleSupport support;
    private final SampleViews views;
    private final SerialNoGenerator serial;
    private final AttachmentService attachments;
    private final DocLogService logs;
    private final ReferenceService references;
    private final StyleService styles;
    private final TechPackService packs;
    private final TagOwnerService tags;

    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<SampleRowVO> page(SampleQuery query) {
        tagFilters.prepare(query);
        SampleSupport.permission("list");SampleSupport.company(query.getCompanyId());
        var page=repository.page(query.getCompanyId(),DataScope.current(false),support.search(query),query.getPage(),query.getSize());
        return new PageResult<>(views.rows(page.getRecords()),page.getTotal());
    }
    @Override public Map<String,Long> counts(SampleQuery query) {
        tagFilters.prepare(query);
        SampleSupport.permission("list");SampleSupport.company(query.getCompanyId());
        Map<String,Long> out=new LinkedHashMap<>();
        for(String state:List.of("WAIT_ASSIGN","MAKING","WAIT_REVIEW","PASSED","CONVERTED","ELIMINATED"))out.put(state,0L);
        var totals=repository.counts(query.getCompanyId(),DataScope.current(false),support.search(query));
        totals.forEach(r->out.put(r.status(),r.total()));
        out.put("ALL",totals.stream().mapToLong(com.lingmao.scm.module.dev.mapper.SampleMapper.StatusCount::total).sum());
        return out;
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SampleDetailVO detail(long companyId,long id) {
        SampleSupport.permission("list");var snapshot=support.read(companyId,id);
        var bundle=repository.bundle(companyId,DataScope.current(false),List.of(snapshot.order().getId()));
        boolean removable=draftOnly(snapshot.sample(),snapshot.order())&&!packs.hasSamplePacks(companyId,id);
        return views.detail(snapshot,views.orders(snapshot,bundle).get(snapshot.order().getId()),removable);
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SampleVersionPage versions(long companyId,long id,Integer beforeVersion,int size) {
        SampleSupport.permission("list");var snapshot=support.read(companyId,id);
        var page=repository.versions(companyId,id,DataScope.current(false),beforeVersion,size);
        var mapped=views.orders(snapshot,repository.bundle(companyId,DataScope.current(false),page.records().stream().map(SampleOrder::getId).toList()));
        return new SampleVersionPage(page.records().stream().map(o->mapped.get(o.getId())).toList(),page.hasMore(),page.nextBeforeVersion());
    }
    @Override public SampleRef byStyle(long companyId,long styleId) {
        SampleSupport.permission("list");SampleSupport.company(companyId);
        Sample s=repository.byStyle(companyId,styleId,DataScope.current(false));
        return s==null?null:new SampleRef(s.getId(),s.getCode(),s.getName(),s.getCompanyId());
    }
    @Override @Transactional
    public SampleCreated create(SampleCreateReq request) {
        SampleSupport.permission("add");SampleSupport.company(request.getCompanyId());
        if(request.getDesignerId()==null)request.setDesignerId(LoginUser.currentUserId());
        Sample sample=new Sample();support.fill(sample,request,null);
        List<SampleRepository.ColorChoice> colors=support.choices(request.getColorIds());SizeGroupVO group=support.group(sample.getSizeGroupId());
        sample.setCode(serial.next("YY"+support.today().format(DateTimeFormatter.ofPattern("yyMM")),4));
        repository.insertSample(sample);repository.replaceColors(sample,request.getColorIds());
        SampleOrder order=newOrder(sample,1,request.getNeedDate());order.setSampleType(SampleType.FIRST);order.setQty(1);
        if(sample.getSampleFactoryId()!=null){order.setMakeWay(MakeWay.EXTERNAL);order.setFactoryId(sample.getSampleFactoryId());}
        else {order.setMakeWay(MakeWay.INTERNAL);order.setMakerId(sample.getPatternMakerId());}
        repository.insertOrder(order);repository.replaceSelection(order,List.of(colors.getFirst()),List.of(group.baseSize()));
        attachments.replace(BizType.SAMPLE,sample.getId(),Attachment.IMAGE,request.getImages());
        logs.add(BizType.SAMPLE,sample.getId(),"create",null,SampleStatus.DEVELOPING,"生成第 1 版打样单 "+order.getNo());
        return new SampleCreated(sample.getId(),sample.getCode(),sample.getCompanyId(),sample.getVersion(),order.getId(),order.getVersion());
    }
    @Override @Transactional
    public SampleActionResult update(long companyId,long id,SampleUpdateReq request) {
        SampleSupport.permission("edit");var locked=support.lock(companyId,id,request.getVersion());SampleSupport.editable(locked.sample());
        Sample before=new Sample();BeanUtils.copyProperties(locked.sample(),before);
        boolean moving=!Objects.equals(before.getCompanyId(),request.getCompanyId());
        boolean groupChanged=!Objects.equals(before.getSizeGroupId(),request.getSizeGroupId());
        boolean neverDispatched=locked.businessVersion()==1&&locked.order().getDispatchedTime()==null&&locked.orderStatus()==SampleOrderStatus.WAIT_ASSIGN;
        if((moving||groupChanged)&&(!neverDispatched||packs.hasSamplePacks(companyId,id)))
            throw new BizException("已经开始打样或建立研发资料包，不能更换主体或尺码组");
        List<Long> oldColors=support.sampleColors(before);Sample after=new Sample();BeanUtils.copyProperties(before,after);support.fill(after,request,before);
        boolean colorsChanged=!oldColors.equals(request.getColorIds());String warning=null;
        // 子单选色触发器即时验证主档成员；先在旧主体写真实新范围，最终组合由延迟守卫校验。
        if(colorsChanged)repository.replaceColors(before,request.getColorIds());
        if(locked.orderStatus()==SampleOrderStatus.WAIT_ASSIGN&&(moving||groupChanged||colorsChanged)) {
            var bundle=repository.bundle(companyId,DataScope.current(false),List.of(locked.orderId()));
            var selected=revisionColors(bundle,request.getColorIds());warning=selected.warning();
            SampleOrder order=locked.order();order.setSizeGroupId(after.getSizeGroupId());
            List<String> sizes=groupChanged?List.of(support.group(after.getSizeGroupId()).baseSize()):bundle.sizes().stream().map(SampleOrderSize::sizeCode).toList();
            repository.updateOrder(locked,order);
            // 当前待派单选择先按旧主体更新；父头迁移随后用固定 SQL 一并移动全部关联。
            repository.replaceSelection(order,selected.colors(),sizes);
        }
        repository.updateSample(locked,after);
        if(moving){locked.order().setCompanyId(after.getCompanyId());tags.ownerChanged(TagObjectType.SAMPLE,id,companyId,after.getCompanyId());}
        logs.add(BizType.SAMPLE,id,"update","修改样衣资料");
        return receipt(after,locked.order(),warning);
    }
    @Override @Transactional
    public SampleRemoved delete(long companyId,long id,int version) {
        SampleSupport.permission("remove");var locked=support.lock(companyId,id,version);
        if(!draftOnly(locked.sample(),locked.order()))throw new BizException("只有首版待派单的样衣可以删除，已经开始开发的请终止");
        if(packs.hasSamplePacks(companyId,id))throw new BizException(1005,"已建立研发资料包，不能删除样衣");
        var bundle=repository.bundle(companyId,DataScope.current(false),List.of(locked.orderId()));
        if(!bundle.nodes().isEmpty()||!bundle.reviews().isEmpty())throw new BizException(1005,"已有开发记录，不能删除样衣");
        references.assertNoReferrer(BizType.SAMPLE,id,"删除");repository.deleteDraft(locked);
        logs.add(BizType.SAMPLE,id,"remove","删除首版草稿，保留历史与审计");tags.ownerChanged(TagObjectType.SAMPLE,id,companyId,companyId);
        return new SampleRemoved(id,companyId,true);
    }
    @Override @Transactional
    public SampleImagesResult images(long companyId,long id,SampleImagesReq request) {
        SampleSupport.permission("edit");var locked=support.lock(companyId,id,request.version());SampleSupport.editable(locked.sample());
        attachments.replace(BizType.SAMPLE,id,Attachment.IMAGE,request.files());repository.touchSample(locked);
        logs.add(BizType.SAMPLE,id,"images","更新样衣图片");
        return new SampleImagesResult(id,companyId,locked.sample().getVersion(),attachments.list(BizType.SAMPLE,id,Attachment.IMAGE));
    }
    @Override @Transactional
    public SampleActionResult stop(long companyId,long id,SampleReasonReq request) {
        SampleSupport.permission("review");String reason=requiredReason(request.reason());var locked=support.lock(companyId,id,request.version());
        Sample sample=locked.sample();SampleOrder order=locked.order();
        order.setStatus(SampleStates.ORDER.next("stop",locked.orderStatus()));sample.setStatus(SampleStates.SAMPLE.next("stop",locked.sampleStatus()));
        repository.updateOrder(locked,order);repository.updateSample(locked,sample);
        logs.add(BizType.SAMPLE,id,"stop",locked.sampleStatus(),sample.getStatus(),reason);
        return receipt(sample,order,null);
    }
    @Override @Transactional
    public SampleActionResult reopen(long companyId,long id,SampleReasonReq request) {
        SampleSupport.permission("review");String reason=requiredReason(request.reason());var locked=support.lock(companyId,id,request.version());
        SampleStatus next=SampleStates.SAMPLE.next("reopen",locked.sampleStatus());
        var revision=newRevision(locked,reason);locked.sample().setStatus(next);repository.updateSample(locked,locked.sample());
        logs.add(BizType.SAMPLE,id,"reopen",locked.sampleStatus(),next,reason);
        return receipt(locked.sample(),revision.order(),revision.warning());
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SampleConvertPreview convertPreview(long companyId,long id,String styleClass) {
        SampleSupport.permission("convert");var snapshot=support.read(companyId,id);
        SampleStates.SAMPLE.next("convert",snapshot.sample().getStatus());
        Sample sample=snapshot.sample();String code=null,error=null;
        try{code=styles.codePreview(styleClass,sample.getDesignerId());}catch(BizException ex){error=ex.getMessage();}
        int skuCount=Math.multiplyExact(support.sampleColors(sample).size(),support.group(sample.getSizeGroupId()).sizes().size());
        var effective=packs.sampleEffectiveHead(companyId,id);
        return new SampleConvertPreview(code,error,skuCount,effective==null?null:new SampleConvertPreview.DevPack(effective.id(),effective.version(),"EFFECTIVE"));
    }
    @Override @Transactional
    public SampleConvertResult convert(long companyId,long id,SampleConvertReq request) {
        SampleSupport.permission("convert");var locked=support.lock(companyId,id,request.version());Sample sample=locked.sample();
        SampleStatus next=SampleStates.SAMPLE.next("convert",locked.sampleStatus());
        if(request.defaultFactoryId()!=null)support.suppliers.checkFactory(request.defaultFactoryId(),com.lingmao.scm.module.supplier.service.SupplierService.Usage.SAMPLE);
        var req=new StyleCreateReq();BeanUtils.copyProperties(sample,req);req.setCodeMode(request.codeMode());req.setCode(request.code());
        req.setStyleClass(request.styleClass());req.setName(request.name());req.setAliasName(request.aliasName());req.setDefaultFactoryId(request.defaultFactoryId());
        req.setColorIds(support.sampleColors(sample));req.setTagPrice(sample.getEstTagPrice());req.setRefCost(sample.getEstCost());
        req.setImages(attachments.list(BizType.SAMPLE,id,Attachment.IMAGE).stream().map(SampleServiceImpl::attachment).toList());
        var style=styles.createFromSample(req,sample.getCode());
        Long prodId=packs.adoptSamplePacks(companyId,id,style.id());
        sample.setStatus(next);sample.setConvertedStyleId(style.id());sample.setConvertBy(LoginUser.currentUserId());sample.setConvertTime(OffsetDateTime.now(support.clock));
        repository.updateSample(locked,sample);logs.add(BizType.SAMPLE,id,"convert",locked.sampleStatus(),next,"转大货 → "+style.code());
        return new SampleConvertResult(style.id(),style.code(),style.skuCount(),prodId,companyId,sample.getVersion());
    }
    @Override @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<DocLogVO> logs(long companyId,long id,PageQuery query) {
        checkReadable(companyId,id);if(query.getSize()>100)throw new BizException(400,"每页最多100条");return logs.page(BizType.SAMPLE,id,query);
    }
    @Override @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<DocRef> references(long companyId,long id){checkReadable(companyId,id);return references.referrers(BizType.SAMPLE,id);}

    @Override @Transactional
    public SampleActionResult saveOrder(long companyId,long orderId,SampleOrderReq request) {
        SampleSupport.permission("edit");var locked=support.lockOrder(companyId,orderId,request.version(),request.sampleVersionLock());SampleSupport.active(locked);
        SampleOrder order=locked.order();boolean draft=locked.orderStatus()==SampleOrderStatus.WAIT_ASSIGN;
        if(!draft&&locked.orderStatus()!=SampleOrderStatus.MAKING)throw new BizException(1002,"待评审的打样单不能改派");
        var bundle=repository.bundle(companyId,DataScope.current(false),List.of(orderId));
        if(!draft){
            List<Long> actualColors=bundle.colors().stream().map(SampleOrderColor::colorId).toList();
            List<String> actualSizes=bundle.sizes().stream().map(SampleOrderSize::sizeCode).toList();
            if(!Objects.equals(request.qty(),order.getQty())||request.sampleType()!=order.getSampleType()||!actualColors.equals(request.colorIds())
                    ||!actualSizes.equals(request.sizeCodes())||!Objects.equals(SampleSupport.trim(request.reason()),order.getReason()))
                throw new BizException(400,"派单后只能修改打样方、要样日期和要求");
        }
        if(order.getSampleVersion()>1)requiredReason(request.reason());
        if(request.makeWay()!=null){
            // 草稿允许尚未选中打样方；一旦指定其中一个身份，仍按真实主档验证。
            if(!draft||request.factoryId()!=null||request.makerId()!=null)support.maker(request.makeWay(),request.factoryId(),request.makerId());
        }else if(!draft||request.factoryId()!=null||request.makerId()!=null)throw new BizException(400,"请先选择制作方式");
        if(draft){
            List<Long> ids=SampleRules.colorIds(request.colorIds(),support.sampleColors(locked.sample()));
            List<String> sizes=SampleRules.sizeCodes(request.sizeCodes(),support.group(order.getSizeGroupId()).sizes());
            order.setSampleType(request.sampleType());order.setQty(request.qty());order.setReason(SampleSupport.trim(request.reason()));
            repository.replaceSelection(order,support.choices(ids),sizes);
        }
        boolean reassigned=order.getMakeWay()!=request.makeWay()||!Objects.equals(order.getMakerId(),request.makerId())||!Objects.equals(order.getFactoryId(),request.factoryId());
        boolean changedDate=!Objects.equals(order.getNeedDate(),request.needDate());
        order.setMakeWay(request.makeWay());order.setMakerId(request.makerId());order.setFactoryId(request.factoryId());order.setNeedDate(request.needDate());
        order.setRequirement(SampleSupport.trim(request.requirement()));
        if(!draft&&reassigned)repository.reassignUndone(locked,defaultOwner(locked.sample(),order));
        repository.updateOrder(locked,order);repository.touchSample(locked);
        logs.add(BizType.SAMPLE,locked.sampleId(),"order-update","修改第 "+locked.businessVersion()+" 版打样单");
        return receipt(locked.sample(),order,!draft&&(reassigned||changedDate)?"节点计划未自动重排，请核对计划日期":null);
    }
    @Override @Transactional
    public SampleActionResult dispatch(long companyId,long orderId,SampleDispatchReq request) {
        SampleSupport.permission("make");var locked=support.lockOrder(companyId,orderId,request.version(),request.sampleVersionLock());SampleSupport.active(locked);
        SampleOrder order=locked.order();SampleOrderStatus next=SampleStates.ORDER.next("dispatch",locked.orderStatus());
        support.maker(request.makeWay(),request.factoryId(),request.makerId());var planned=support.plans(request.needDate());
        order.setMakeWay(request.makeWay());order.setFactoryId(request.factoryId());order.setMakerId(request.makerId());order.setNeedDate(request.needDate());
        order.setDispatchedBy(LoginUser.currentUserId());order.setDispatchedTime(OffsetDateTime.now(support.clock));order.setStatus(next);
        repository.updateOrder(locked,order);repository.insertNodes(order,planned,defaultOwner(locked.sample(),order));repository.touchSample(locked);
        logs.add(BizType.SAMPLE,locked.sampleId(),"dispatch","第 "+locked.businessVersion()+" 版派单，要样日期 "+request.needDate());
        return receipt(locked.sample(),order,null);
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SampleDispatchPreview dispatchPreview(long companyId,long orderId,LocalDate needDate) {
        SampleSupport.permission("make");SampleSupport.company(companyId);
        var order=repository.orderIdentity(companyId,orderId,DataScope.current(false));var parent=support.read(companyId,order.getSampleId());
        if(!Objects.equals(parent.order().getId(),orderId)||order.getStatus()!=SampleOrderStatus.WAIT_ASSIGN)throw new BizException(1002,"该版已派单或不是当前版");
        return new SampleDispatchPreview(support.plans(needDate).stream().map(n->new SampleDispatchPreview.Node(n.seq(),n.code(),n.name(),n.durationDays(),n.planDate())).toList());
    }
    @Override @Transactional
    public SampleActionResult plans(long companyId,long orderId,SampleNodePlansReq request) {
        SampleSupport.permission("make");var locked=support.lockOrder(companyId,orderId,request.version(),request.sampleVersionLock());SampleSupport.active(locked);
        if(locked.orderStatus()!=SampleOrderStatus.MAKING)throw new BizException(1002,"只有打样中可以修改节点计划");
        support.enabledPeople(request.nodes().stream().map(SampleNodePlansReq.Node::ownerId).toList());
        List<SampleNode> plans=request.nodes().stream().map(n->{var row=new SampleNode();row.setId(n.id());row.setPlanDate(n.planDate());row.setOwnerId(n.ownerId());row.setRemark(SampleSupport.trim(n.remark()));return row;}).toList();
        repository.updatePlans(locked,plans);repository.updateOrder(locked,locked.order());repository.touchSample(locked);
        logs.add(BizType.SAMPLE,locked.sampleId(),"node-plan","修改第 "+locked.businessVersion()+" 版节点计划");return receipt(locked.sample(),locked.order(),null);
    }
    @Override @Transactional
    public SampleActionResult done(long companyId,long orderId,long nodeId,SampleNodeDoneReq request) {
        return nodeDone(companyId,orderId,nodeId,request.version(),request.sampleVersionLock(),request.doneDate());
    }
    @Override @Transactional
    public SampleActionResult undoDone(long companyId,long orderId,long nodeId,int version,int sampleVersionLock) {
        return nodeDone(companyId,orderId,nodeId,version,sampleVersionLock,null);
    }
    private SampleActionResult nodeDone(long companyId,long orderId,long nodeId,int version,int parentVersion,LocalDate date) {
        SampleSupport.permission("make");var locked=support.lockOrder(companyId,orderId,version,parentVersion);SampleSupport.active(locked);
        if(!Set.of(SampleOrderStatus.MAKING,SampleOrderStatus.WAIT_REVIEW).contains(locked.orderStatus()))throw new BizException(1002,"当前版还未派单或已评审");
        List<SampleNode> nodes=repository.lockNodes(locked);SampleNode node=nodes.stream().filter(n->n.getId()==nodeId).findFirst().orElseThrow(()->new BizException(404,"节点不存在"));
        if(date!=null)SampleRules.notFuture(date,support.today(),"完成日期");
        String warning=date!=null&&nodes.stream().anyMatch(n->n.getSeq()<node.getSeq()&&n.getDoneDate()==null)?"前面还有节点没报完成":null;
        repository.setDone(locked,nodeId,date);
        if("SEND".equals(node.getNodeCode()))locked.order().setStatus(SampleStates.ORDER.next(date==null?"unsend":"send",locked.orderStatus()));
        repository.updateOrder(locked,locked.order());repository.touchSample(locked);
        logs.add(BizType.SAMPLE,locked.sampleId(),date==null?"node-undo":"node-done","第 "+locked.businessVersion()+" 版「"+node.getNodeName()+"」"+(date==null?"撤回完成":"完成 "+date));
        return receipt(locked.sample(),locked.order(),warning);
    }
    @Override @Transactional
    public SampleActionResult review(long companyId,long orderId,SampleReviewReq request) {
        SampleSupport.permission("review");
        // Revision opinions become the next order's reason; validate the original input before writing either record.
        if(request.result()==SampleReviewResult.REVISE&&request.opinion()!=null&&request.opinion().length()>500)
            throw new BizException(400,"改版评审意见最多500个字符");
        var locked=support.lockOrder(companyId,orderId,request.version(),request.sampleVersionLock());SampleSupport.active(locked);
        String action=switch(request.result()){case PASS->"pass";case REVISE->"revise";case ELIMINATE->"eliminate";};
        SampleOrderStatus orderStatus=SampleStates.ORDER.next(action,locked.orderStatus());SampleStatus status=SampleStates.SAMPLE.next(action,locked.sampleStatus());
        if(request.result()!=SampleReviewResult.PASS)requiredReason(request.opinion());
        SampleRules.notFuture(request.reviewDate(),support.today(),"评审日期");List<Long> reviewers=SampleRules.reviewerIds(request.reviewerIds());support.enabledPeople(reviewers);
        SampleOrder order=locked.order();order.setStatus(orderStatus);repository.updateOrder(locked,order);
        SampleReview review=new SampleReview();review.setReviewDate(request.reviewDate());review.setResult(request.result());review.setOpinion(SampleSupport.trim(request.opinion()));
        repository.insertReview(order,review,reviewers);attachments.replace(BizType.SAMPLE_REVIEW,review.getId(),Attachment.IMAGE,request.photos());
        Revision revision=request.result()==SampleReviewResult.REVISE?newRevision(locked,request.opinion()):null;
        locked.sample().setStatus(status);repository.updateSample(locked,locked.sample());
        // 完整意见保存在评审表；时间轴只定位该评审，不把最多1000字复制进500字日志列。
        logs.add(BizType.SAMPLE,locked.sampleId(),"review",locked.sampleStatus(),status,
                "第 "+locked.businessVersion()+" 版评审 "+request.result()+"，评审记录 "+review.getId());
        return receipt(locked.sample(),revision==null?order:revision.order(),revision==null?null:revision.warning());
    }
    @Override @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<AttachmentVO> orderFiles(long companyId,long orderId){checkOrderReadable(companyId,orderId);return attachments.list(BizType.SAMPLE_ORDER,orderId,SampleViews.ORDER_FILES);}
    @Override @Transactional
    public SampleOrderFilesResult orderFiles(long companyId,long orderId,SampleOrderFilesReq request) {
        SampleSupport.permission("edit");var locked=support.lockOrder(companyId,orderId,request.version(),request.sampleVersionLock());SampleSupport.active(locked);
        if(!Set.of(SampleOrderStatus.WAIT_ASSIGN,SampleOrderStatus.MAKING).contains(locked.orderStatus()))
            throw new BizException(1002,"只有待派单或打样中可以修改打样附件");
        attachments.replace(BizType.SAMPLE_ORDER,orderId,SampleViews.ORDER_FILES,request.files());repository.updateOrder(locked,locked.order());repository.touchSample(locked);
        logs.add(BizType.SAMPLE,locked.sampleId(),"order-files","更新第 "+locked.businessVersion()+" 版附件");
        return SampleOrderFilesResult.of(receipt(locked.sample(),locked.order(),null),attachments.list(BizType.SAMPLE_ORDER,orderId,SampleViews.ORDER_FILES));
    }
    @Override public void checkReadable(long companyId,long sampleId){SampleSupport.permission("list");support.read(companyId,sampleId);}
    @Override public void checkOrderReadable(long companyId,long orderId){SampleSupport.permission("list");SampleSupport.company(companyId);repository.orderIdentity(companyId,orderId,DataScope.current(false));}
    @Override public void checkReviewReadable(long companyId,long reviewId){SampleSupport.permission("list");SampleSupport.company(companyId);repository.reviewIdentity(companyId,reviewId,DataScope.current(false));}
    @Override public void authorizeReplay(String permission,long companyId,long sampleId,boolean removed){
        SampleSupport.permission(permission);SampleSupport.company(companyId);
        if(removed)repository.replayIdentity(companyId,sampleId,DataScope.current(false));else repository.read(companyId,sampleId,DataScope.current(false));
    }
    @Override public void authorizeUpdateReplay(long originalCompanyId,long resultingCompanyId,long sampleId){
        SampleSupport.permission("edit");SampleSupport.company(originalCompanyId);
        if(resultingCompanyId<=0)throw new IllegalStateException("样衣保存收据缺少真实结果主体");
        // 原请求的主体绑定保持不变；只有当前真实归属和当前SELF/ALL范围仍允许时才重放收据。
        repository.read(resultingCompanyId,sampleId,DataScope.current(false));
    }
    @Override public void authorizeOrderReplay(String permission,long companyId,long orderId){SampleSupport.permission(permission);SampleSupport.company(companyId);repository.orderIdentity(companyId,orderId,DataScope.current(false));}

    private record Selection(List<SampleRepository.ColorChoice> colors,String warning){}
    private record Revision(SampleOrder order,String warning){}
    private Selection revisionColors(SampleRepository.OrderBundle bundle,List<Long> current) {
        List<SampleOrderColor> previous=bundle.colors().stream().sorted(Comparator.comparing(SampleOrderColor::seq)).toList();
        var result=SampleRules.revisionColors(previous.stream().map(SampleOrderColor::colorId).toList(),current);
        Map<Long,SampleOrderColor> snapshots=previous.stream().collect(Collectors.toMap(SampleOrderColor::colorId,c->c));
        List<SampleRepository.ColorChoice> colors=result.usedFallback()?support.choices(result.colorIds()):result.colorIds().stream().map(id->{var c=snapshots.get(id);return new SampleRepository.ColorChoice(id,c.colorCode(),c.colorName(),c.colorHex());}).toList();
        String warning=result.removedIds().isEmpty()?null:"已从新版本移除主档不再选择的颜色："+result.removedIds().stream().map(id->{var c=snapshots.get(id);return c.colorCode()+" "+c.colorName();}).collect(Collectors.joining("、"));
        if(result.usedFallback())warning+="；原选色均失效，已选择当前主档第一色 "+colors.getFirst().code()+" "+colors.getFirst().name();
        return new Selection(colors,warning);
    }
    private Revision newRevision(SampleRepository.Locked locked,String reason) {
        var bundle=repository.bundle(locked.companyId(),DataScope.current(false),List.of(locked.orderId()));
        var selected=revisionColors(bundle,support.sampleColors(locked.sample()));SampleOrder prior=locked.order();
        SampleOrder next=newOrder(locked.sample(),repository.nextVersion(locked),support.today().plusDays(7));
        next.setSampleType(SampleType.REVISE);next.setQty(prior.getQty());next.setMakeWay(prior.getMakeWay());next.setFactoryId(prior.getFactoryId());next.setMakerId(prior.getMakerId());
        next.setReason(requiredReason(reason));next.setRequirement(prior.getRequirement());repository.insertOrder(next);
        repository.replaceSelection(next,selected.colors(),bundle.sizes().stream().sorted(Comparator.comparing(SampleOrderSize::seq)).map(SampleOrderSize::sizeCode).toList());
        return new Revision(next,selected.warning());
    }
    private SampleOrder newOrder(Sample sample,int businessVersion,LocalDate needDate) {
        var order=new SampleOrder();order.setCompanyId(sample.getCompanyId());order.setSampleId(sample.getId());order.setSampleVersion(businessVersion);
        order.setSizeGroupId(sample.getSizeGroupId());order.setNeedDate(needDate);order.setNo(serial.daily("DY"));return order;
    }
    private static boolean draftOnly(Sample sample,SampleOrder order){return sample.getStatus()==SampleStatus.DEVELOPING&&order.getSampleVersion()==1&&order.getStatus()==SampleOrderStatus.WAIT_ASSIGN&&order.getDispatchedTime()==null;}
    private static Long defaultOwner(Sample sample,SampleOrder order){return order.getMakeWay()==MakeWay.INTERNAL?order.getMakerId():sample.getDesignerId();}
    private static String requiredReason(String value){String reason=SampleSupport.trim(value);if(reason==null)throw new BizException(400,"请填写原因或评审意见");return reason;}
    private static SampleActionResult receipt(Sample sample,SampleOrder order,String warning){return new SampleActionResult(sample.getId(),sample.getCompanyId(),sample.getVersion(),order.getId(),order.getVersion(),order.getSampleVersion(),sample.getStatus(),order.getStatus(),warning);}
    private static AttachmentReq attachment(AttachmentVO file){var out=new AttachmentReq();out.setUrl(file.url());out.setThumbUrl(file.thumbUrl());out.setName(file.name());out.setSize(file.size());return out;}
}
