package com.lingmao.scm.module.dev.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.service.*;
import com.lingmao.scm.module.dev.core.SampleNodePlanner;
import com.lingmao.scm.module.dev.core.SampleRules;
import com.lingmao.scm.module.dev.dto.SampleFields;
import com.lingmao.scm.module.dev.dto.SampleQuery;
import com.lingmao.scm.module.dev.entity.*;
import com.lingmao.scm.module.dev.mapper.SampleSearch;
import com.lingmao.scm.module.dev.repository.SampleRepository;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.FieldPermissions;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** 只持有本域 repository 与公开基础服务；不得反向依赖 TechPackService。 */
@Component
@RequiredArgsConstructor
class SampleSupport {
    final SampleRepository repository;
    final CategoryService categories;
    final ColorService colors;
    final SizeGroupService sizes;
    final CompanyService companies;
    final UserService users;
    final DictDataService dictionaries;
    final SupplierService suppliers;
    final TradeFeature trade;
    final Clock clock;

    static void permission(String action) {
        if (!LoginUser.hasAuthority("dev:sample:"+action)) throw new BizException(403,"没有样衣操作权限");
    }
    static boolean allowed(String action) { return LoginUser.hasAuthority("dev:sample:"+action); }
    static void company(long companyId) {
        if (companyId <= 0) throw new BizException(400,"请选择主体公司");
        if (CompanyContext.isHttpRequest() && !Objects.equals(CompanyContext.header(true),companyId))
            throw new BizException(403,"当前页面主体与样衣不一致");
    }
    SampleRepository.Snapshot read(long companyId,long id) { company(companyId); return repository.read(companyId,id,DataScope.current(false)); }
    SampleRepository.Locked lock(long companyId,long id,Integer version) {
        company(companyId);return repository.lock(companyId,id,DataScope.current(false),version,null);
    }
    SampleRepository.Locked lockOrder(long companyId,long id,int version,int parentVersion) {
        company(companyId);return repository.lockByOrder(companyId,id,DataScope.current(false),parentVersion,version);
    }
    static void editable(Sample sample) {
        if (sample.getStatus()!=SampleStatus.DEVELOPING && sample.getStatus()!=SampleStatus.PASSED)
            throw new BizException("样衣已经终止或转款，不能修改");
    }
    static void active(SampleRepository.Locked locked) {
        if (locked.sampleStatus()!=SampleStatus.DEVELOPING || !Set.of(SampleOrderStatus.WAIT_ASSIGN,
                SampleOrderStatus.MAKING,SampleOrderStatus.WAIT_REVIEW).contains(locked.orderStatus()))
            throw new BizException(1002,"当前打样版已结束，请刷新后再试");
    }
    SizeGroupVO group(long id) {
        SizeGroupVO group=sizes.getByIdsBounded(List.of(id),100).get(id);
        if (group==null || group.sizes().isEmpty() || !group.sizes().contains(group.baseSize()))
            throw new BizException(400,"尺码组没有有效基码");
        return group;
    }
    List<Color> colorRows(Collection<Long> ids) {
        List<Long> ordered=SampleRules.colorIds(ids,null); Map<Long,Color> found=colors.byIds(ordered);
        if (found.size()!=ordered.size()) throw new BizException(400,"所选颜色不存在");
        return ordered.stream().map(found::get).toList();
    }
    List<SampleRepository.ColorChoice> choices(Collection<Long> ids) {
        return colorRows(ids).stream().map(c->new SampleRepository.ColorChoice(c.getId(),c.getCode(),c.getName(),c.getHex())).toList();
    }
    List<Long> sampleColors(Sample sample) {
        return repository.colors(sample.getCompanyId(),sample.getId(),DataScope.current(false)).stream().map(SampleColor::colorId).toList();
    }
    void enabledPeople(Collection<Long> ids) {
        List<Long> nonNull=ids.stream().filter(Objects::nonNull).distinct().toList();
        if (!users.enabledIds(nonNull).containsAll(nonNull)) throw new BizException(400,"所选人员不存在或已停用");
    }
    void maker(MakeWay way,Long factory,Long maker) {
        if (way==null) throw new BizException(400,"请选择制作方式");
        if (way==MakeWay.EXTERNAL) {
            if (factory==null || maker!=null) throw new BizException(400,"外部制作须选择工厂，不能同时选择内部版师");
            suppliers.checkFactory(factory,SupplierService.Usage.SAMPLE);
        } else {
            if (maker==null || factory!=null) throw new BizException(400,"内部制作须选择版师，不能同时选择外部工厂");
            enabledPeople(List.of(maker));
        }
    }
    void fill(Sample target,SampleFields req,Sample old) {
        if (old==null || !Objects.equals(old.getCompanyId(),req.getCompanyId())) {
            if (!Objects.equals(companies.getById(req.getCompanyId()).getStatus(),1)) throw new BizException(400,"主体公司已停用");
        }
        dict("brand",req.getBrand(),old==null?null:old.getBrand(),"品牌");
        dict("season",req.getSeason(),old==null?null:old.getSeason(),"季节");
        dict("wave",req.getWave(),old==null?null:old.getWave(),"波段");
        dict("style_source",req.getSource(),old==null?null:old.getSource(),"来源");
        trade.rejectIfOff("CUSTOMER_SAMPLE".equals(req.getSource()),"客户来样");
        trade.rejectIfOff(req.getCustomerId()!=null || trim(req.getCustomerStyleNo())!=null,"客户资料");
        if (old==null || !Objects.equals(old.getCategoryId(),req.getCategoryId())) categories.checkSelectable(req.getCategoryId());
        SizeGroupVO group=group(req.getSizeGroupId());
        if ((old==null || !Objects.equals(old.getSizeGroupId(),req.getSizeGroupId())) && !Objects.equals(group.status(),1))
            throw new BizException(400,"尺码组已停用");
        List<Long> previous=old==null?List.of():sampleColors(old);
        List<Color> selected=colorRows(req.getColorIds());
        if (selected.stream().anyMatch(c->!previous.contains(c.getId()) && !Objects.equals(c.getStatus(),1)))
            throw new BizException(400,"新选颜色已经停用");
        SampleRules.combinations(selected.size(),group.sizes().size());
        var changedUsers=new ArrayList<Long>();
        if (old==null || !Objects.equals(old.getDesignerId(),req.getDesignerId())) changedUsers.add(req.getDesignerId());
        if (old==null || !Objects.equals(old.getPatternMakerId(),req.getPatternMakerId())) changedUsers.add(req.getPatternMakerId());
        enabledPeople(changedUsers);
        if (req.getSampleFactoryId()!=null && (old==null || !Objects.equals(old.getSampleFactoryId(),req.getSampleFactoryId())))
            suppliers.checkFactory(req.getSampleFactoryId(),SupplierService.Usage.SAMPLE);
        if (req.getFabricSupplierId()!=null && (old==null || !Objects.equals(old.getFabricSupplierId(),req.getFabricSupplierId())))
            suppliers.checkUsable(req.getFabricSupplierId(),SupplierService.Usage.SAMPLE);
        target.setCompanyId(req.getCompanyId());target.setName(trim(req.getName()));target.setBrand(trim(req.getBrand()));
        target.setCategoryId(req.getCategoryId());target.setYear(req.getYear());target.setSeason(trim(req.getSeason()));target.setWave(trim(req.getWave()));
        target.setDesignerId(req.getDesignerId());target.setPatternMakerId(req.getPatternMakerId());target.setSource(trim(req.getSource()));
        target.setCustomerId(req.getCustomerId());target.setCustomerStyleNo(trim(req.getCustomerStyleNo()));target.setSizeGroupId(req.getSizeGroupId());
        target.setSampleFactoryId(req.getSampleFactoryId());target.setFabricSupplierId(req.getFabricSupplierId());target.setFabricDesc(trim(req.getFabricDesc()));
        target.setExpectFinishDate(req.getExpectFinishDate());target.setEstTagPrice(decimal(req.getEstTagPrice()));
        target.setEstCost(FieldPermissions.price(FieldPermissions.COST,old==null?null:old.getEstCost(),decimal(req.getEstCost())));
        target.setRemark(trim(req.getRemark()));
    }
    private void dict(String type,String value,String previous,String label) {
        String v=trim(value);if(v==null || Objects.equals(v,previous))return;
        if(dictionaries.listByType(type).stream().noneMatch(d->d.enabled() && d.value().equals(v)))
            throw new BizException(400,label+"不存在或已停用");
    }
    List<SampleNodePlanner.Planned> plans(LocalDate needDate) {
        if(needDate==null)throw new BizException(400,"请填写需样日期");
        return SampleNodePlanner.plan(needDate,dictionaries.enabledForUse("sample_node",100).stream()
                .map(d->new SampleNodePlanner.Template(d.getValue(),d.getLabel(),d.getNumValue())).toList());
    }
    SampleSearch search(SampleQuery q) {
        return new SampleSearch(trim(q.getKeyword()),trim(q.getStatus()),descendants(q.getCategoryId()),q.getYear(),trim(q.getSeason()),
                trim(q.getBrand()),q.getDesignerId(),q.getFactoryId(),q.getMakerId(),trim(q.getNodeCode()),q.isLate(),
                q.getNeedFrom(),q.getNeedTo(),q.getCreatedFrom(),q.getCreatedTo(),SampleSearch.Sort.valueOf(q.getSort().name()));
    }
    private List<Long> descendants(Long id) {
        if(id==null)return null;
        var todo=new ArrayDeque<TreeNode>(categories.tree());TreeNode selected=null;
        while(!todo.isEmpty()) {TreeNode n=todo.removeFirst();if(n.getId().equals(id)){selected=n;break;}todo.addAll(n.getChildren());}
        if(selected==null)return List.of();
        todo.clear();todo.add(selected);var out=new ArrayList<Long>();
        while(!todo.isEmpty()){TreeNode n=todo.removeFirst();out.add(n.getId());if(out.size()>5000)throw new BizException(400,"品类范围超过5000项");todo.addAll(n.getChildren());}
        return out;
    }
    LocalDate today(){return LocalDate.now(clock);}
    static String trim(String value){return value==null||value.isBlank()?null:value.trim();}
    static BigDecimal decimal(String value){return value==null?null:new BigDecimal(value);}
    static String money(BigDecimal value){return value==null?null:value.toPlainString();}
}
