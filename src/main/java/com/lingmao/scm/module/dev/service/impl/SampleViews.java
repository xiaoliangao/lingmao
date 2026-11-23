package com.lingmao.scm.module.dev.service.impl;

import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.dev.dto.*;
import com.lingmao.scm.module.dev.entity.*;
import com.lingmao.scm.module.dev.mapper.SampleRow;
import com.lingmao.scm.module.dev.repository.SampleRepository;
import com.lingmao.scm.module.style.service.StyleService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** 每页/历史批次只做批量补查；持久实体不直接进入 HTTP JSON。 */
@Component
@RequiredArgsConstructor
class SampleViews {
    static final String ORDER_FILES="SAMPLE_FILES";
    final SampleSupport support;
    final AttachmentService attachments;
    final StyleService styles;

    List<SampleRowVO> rows(List<SampleRow> rows) {
        Map<Long,AttachmentVO> images=attachments.firsts(BizType.SAMPLE,rows.stream().map(Sample::getId).toList(),Attachment.IMAGE);
        return rows.stream().map(r->{
            var out=new SampleRowVO();BeanUtils.copyProperties(r,out,"estCost","estTagPrice","late");
            out.setEstCost(SampleSupport.money(r.getEstCost()));out.setEstTagPrice(SampleSupport.money(r.getEstTagPrice()));
            out.setLate(Boolean.TRUE.equals(r.getLate()));out.setImage(images.get(r.getId()));
            if(r.getCurrentNodeId()!=null)out.setCurrentNode(new SampleCurrentNode(r.getCurrentNodeId(),r.getCurrentNodeCode(),
                    r.getCurrentNodeName(),r.getCurrentNodePlanDate(),r.getCurrentNodeOwnerId(),r.getCurrentNodeOwnerName(),
                    nodeState(null,r.getCurrentNodePlanDate(),true,support.today())));
            return out;
        }).toList();
    }
    SampleDetailVO detail(SampleRepository.Snapshot snapshot,SampleOrderVO order,boolean removable) {
        Sample s=snapshot.sample();var out=new SampleDetailVO();BeanUtils.copyProperties(s,out,"estCost","estTagPrice");
        out.setEstCost(SampleSupport.money(s.getEstCost()));out.setEstTagPrice(SampleSupport.money(s.getEstTagPrice()));
        out.setShowStatus(showStatus(s,snapshot.order()));
        var group=support.group(s.getSizeGroupId());out.setSizes(group.sizes());out.setSizeGroupName(group.name());out.setBaseSize(group.baseSize());
        List<Long> ids=support.sampleColors(s);out.setColorIds(ids);out.setColors(support.colorRows(ids).stream().map(ColorOption::from).toList());
        out.setCategoryName(support.categories.getById(s.getCategoryId()).getName());
        var people=support.users.nicknames(nonNull(s.getDesignerId(),s.getPatternMakerId()));
        out.setDesignerName(name(people,s.getDesignerId()));out.setPatternMakerName(name(people,s.getPatternMakerId()));
        var suppliers=support.suppliers.refs(nonNull(s.getSampleFactoryId(),s.getFabricSupplierId()));
        if(s.getSampleFactoryId()!=null&&suppliers.containsKey(s.getSampleFactoryId()))out.setSampleFactoryName(suppliers.get(s.getSampleFactoryId()).shortName());
        if(s.getFabricSupplierId()!=null&&suppliers.containsKey(s.getFabricSupplierId()))out.setFabricSupplierName(suppliers.get(s.getFabricSupplierId()).shortName());
        if(s.getConvertedStyleId()!=null){var ref=styles.refs(List.of(s.getConvertedStyleId())).get(s.getConvertedStyleId());if(ref!=null)out.setConvertedStyleCode(ref.code());}
        out.setImages(attachments.list(BizType.SAMPLE,s.getId(),Attachment.IMAGE));out.setCurrentOrder(order);
        boolean editable=Set.of(SampleStatus.DEVELOPING,SampleStatus.PASSED).contains(s.getStatus());
        out.setActions(new SampleDetailVO.Actions(editable&&SampleSupport.allowed("edit"),removable&&SampleSupport.allowed("remove"),
                s.getStatus()==SampleStatus.DEVELOPING&&SampleSupport.allowed("review"),
                Set.of(SampleStatus.PASSED,SampleStatus.ELIMINATED).contains(s.getStatus())&&SampleSupport.allowed("review"),
                s.getStatus()==SampleStatus.PASSED&&SampleSupport.allowed("convert")));
        return out;
    }
    Map<Long,SampleOrderVO> orders(SampleRepository.Snapshot parent,SampleRepository.OrderBundle bundle) {
        Set<Long> personIds=new HashSet<>();Set<Long> factoryIds=new HashSet<>();
        for(SampleOrder o:bundle.orders()){if(o.getMakerId()!=null)personIds.add(o.getMakerId());if(o.getFactoryId()!=null)factoryIds.add(o.getFactoryId());}
        for(SampleNode n:bundle.nodes()){if(n.getOwnerId()!=null)personIds.add(n.getOwnerId());if(n.getDoneBy()!=null)personIds.add(n.getDoneBy());}
        bundle.reviewers().forEach(r->personIds.add(r.userId()));
        Map<Long,String> people=support.users.nicknames(personIds);var factories=support.suppliers.refs(factoryIds);
        var colors=bundle.colors().stream().collect(Collectors.groupingBy(SampleOrderColor::sampleOrderId));
        var sizes=bundle.sizes().stream().collect(Collectors.groupingBy(SampleOrderSize::sampleOrderId));
        var nodes=bundle.nodes().stream().collect(Collectors.groupingBy(SampleNode::getSampleOrderId));
        var reviews=bundle.reviews().stream().collect(Collectors.toMap(SampleReview::getSampleOrderId,r->r));
        var reviewers=bundle.reviewers().stream().collect(Collectors.groupingBy(SampleReviewer::reviewId));
        var photos=attachments.lists(BizType.SAMPLE_REVIEW,bundle.reviews().stream().map(SampleReview::getId).toList(),Attachment.IMAGE);
        Map<Long,SampleOrderVO> out=new LinkedHashMap<>();
        for(SampleOrder o:bundle.orders()){
            List<SampleNode> ns=nodes.getOrDefault(o.getId(),List.of()).stream().sorted(Comparator.comparing(SampleNode::getSeq)).toList();
            Long first=ns.stream().filter(n->n.getDoneDate()==null).map(SampleNode::getId).findFirst().orElse(null);
            List<SampleNodeVO> nodeViews=ns.stream().map(n->new SampleNodeVO(n.getId(),n.getSeq(),n.getNodeCode(),n.getNodeName(),n.getDurationDays(),
                    n.getPlanDate(),n.getDoneDate(),n.getDoneBy(),name(people,n.getDoneBy()),n.getOwnerId(),name(people,n.getOwnerId()),n.getRemark(),
                    nodeState(n.getDoneDate(),n.getPlanDate(),Objects.equals(first,n.getId()),support.today()))).toList();
            SampleReview review=reviews.get(o.getId());SampleReviewVO reviewVO=review==null?null:new SampleReviewVO(review.getId(),review.getReviewDate(),
                    review.getResult(),review.getOpinion(),reviewers.getOrDefault(review.getId(),List.of()).stream().sorted(Comparator.comparing(SampleReviewer::seq))
                    .map(r->new SampleReviewVO.Person(r.userId(),people.get(r.userId()))).toList(),photos.getOrDefault(review.getId(),List.of()));
            boolean current=Objects.equals(o.getId(),parent.order().getId());boolean active=current&&parent.sample().getStatus()==SampleStatus.DEVELOPING;
            boolean running=active&&Set.of(SampleOrderStatus.MAKING,SampleOrderStatus.WAIT_REVIEW).contains(o.getStatus());
            var actions=new SampleOrderVO.Actions(active&&Set.of(SampleOrderStatus.WAIT_ASSIGN,SampleOrderStatus.MAKING).contains(o.getStatus())&&SampleSupport.allowed("edit"),active&&o.getStatus()==SampleOrderStatus.WAIT_ASSIGN&&SampleSupport.allowed("make"),
                    running&&SampleSupport.allowed("make"),active&&o.getStatus()==SampleOrderStatus.WAIT_REVIEW&&SampleSupport.allowed("review"));
            List<ColorOption> cs=colors.getOrDefault(o.getId(),List.of()).stream().sorted(Comparator.comparing(SampleOrderColor::seq))
                    .map(c->new ColorOption(c.colorId(),c.colorCode(),c.colorName(),c.colorHex())).toList();
            List<String> sz=sizes.getOrDefault(o.getId(),List.of()).stream().sorted(Comparator.comparing(SampleOrderSize::seq)).map(SampleOrderSize::sizeCode).toList();
            out.put(o.getId(),new SampleOrderVO(o.getId(),o.getSampleId(),o.getCompanyId(),o.getNo(),o.getVersion(),parent.sample().getVersion(),o.getSampleVersion(),
                    o.getSampleType(),current,o.getStatus(),o.getMakeWay(),o.getFactoryId(),o.getFactoryId()!=null&&factories.containsKey(o.getFactoryId())?factories.get(o.getFactoryId()).shortName():null,
                    o.getMakerId(),name(people,o.getMakerId()),o.getNeedDate(),o.getQty(),o.getSizeGroupId(),cs,sz,o.getReason(),o.getRequirement(),nodeViews,reviewVO,actions));
        }
        return out;
    }
    static String showStatus(Sample sample,SampleOrder order){return sample.getStatus()==SampleStatus.DEVELOPING?order.getStatus().name():sample.getStatus().name();}
    static String nodeState(LocalDate done,LocalDate plan,boolean first,LocalDate today){
        if(done!=null)return "DONE";
        if(plan.isBefore(today))return "LATE";
        if(!plan.isAfter(today.plusDays(1)))return "WARN";
        return first?"RUNNING":"TODO";
    }
    private static String name(Map<Long,String> names,Long id){return id==null?null:names.get(id);}
    private static List<Long> nonNull(Long...ids){return Arrays.stream(ids).filter(Objects::nonNull).distinct().toList();}
}
