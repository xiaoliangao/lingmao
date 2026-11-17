package com.lingmao.scm.module.techpack.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.service.ColorService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.style.entity.TechPackStatus;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.dto.FileVO;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.module.techpack.dto.BomDefaultReq;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import com.lingmao.scm.module.techpack.dto.BomItemVO;
import com.lingmao.scm.module.techpack.dto.BomSkuVO;
import com.lingmao.scm.module.techpack.dto.CraftSectionForm;
import com.lingmao.scm.module.techpack.dto.CraftSectionVO;
import com.lingmao.scm.module.techpack.dto.PackOwner;
import com.lingmao.scm.module.techpack.dto.ProcessVO;
import com.lingmao.scm.module.techpack.dto.PublishProblem;
import com.lingmao.scm.module.techpack.dto.SpecPointVO;
import com.lingmao.scm.module.techpack.dto.PackBrief;
import com.lingmao.scm.module.techpack.dto.TechPackBriefVO;
import com.lingmao.scm.module.techpack.dto.TechPackDetailVO;
import com.lingmao.scm.module.techpack.dto.TechPackOverviewVO;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import com.lingmao.scm.module.techpack.entity.CostMode;
import com.lingmao.scm.module.techpack.entity.CraftSection;
import com.lingmao.scm.module.techpack.entity.CraftText;
import com.lingmao.scm.module.techpack.entity.PackProcess;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.mapper.BomItemSkuMapper;
import com.lingmao.scm.module.techpack.mapper.PackProcessMapper;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.module.techpack.service.CraftStandardService;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.module.techpack.service.impl.PackContent.BomRow;
import com.lingmao.scm.module.techpack.service.impl.PackContent.Content;
import com.lingmao.scm.module.techpack.service.impl.PackOwners.Ctx;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lingmao.scm.module.techpack.entity.PackStatus.DRAFT;
import static com.lingmao.scm.module.techpack.entity.PackStatus.EFFECTIVE;
import static com.lingmao.scm.module.techpack.entity.PackStatus.VOIDED;

@Service
@RequiredArgsConstructor
public class TechPackServiceImpl implements TechPackService {

    /**
     * 状态转换表（docs/api/01 §2）。按钮顺序就是这里的顺序；void 是生效时旧版跟着走的，不出按钮（不在 ACTION_PERMS 里）。
     * 删草稿、修订、转生产 BOM 状态不变（修订、转生产 BOM 是从这一版复制出新草稿），写成到自己
     */
    static final StatusFlow<PackStatus> FLOW = StatusFlow.of(PackStatus.class)
            .on("edit", DRAFT).to(DRAFT)
            .on("publish", DRAFT).to(EFFECTIVE)
            .on("toProd", EFFECTIVE).to(EFFECTIVE)
            .on("revise", EFFECTIVE).to(EFFECTIVE)
            .on("remove", DRAFT).to(DRAFT)
            .on("void", EFFECTIVE).to(VOIDED)
            .build();

    /** 按钮 → 权限码（docs/api/01 §1.4） */
    static final Map<String, String> ACTION_PERMS = Map.of(
            "edit", "techpack:pack:edit",
            "publish", "techpack:pack:publish",
            "toProd", "techpack:pack:add",
            "revise", "techpack:pack:add",
            "remove", "techpack:pack:remove");

    /** 操作记录的动作（docs/api/01 §3.11）；导出 PDF 的 export-pdf 在 TechPackPdfService */
    static final String LOG_CREATE = "create";
    static final String LOG_UPDATE = "update";
    static final String LOG_REVISE = "revise";
    static final String LOG_TO_PROD = "to-prod";
    static final String LOG_PUBLISH = "publish";
    static final String LOG_VOID = "void";

    static final String COST_PERM = "techpack:cost:query";

    private static final String NOT_FOUND = "这一版资料包不存在，可能已经被删除了";

    private final TechPackMapper techPackMapper;
    private final BomItemSkuMapper bomItemSkuMapper;
    private final PackProcessMapper packProcessMapper;
    private final PackContent packContent;
    private final PackOwners packOwners;
    private final PackForms packForms;
    private final BomPricing bomPricing;
    private final CostCalculator costCalculator;
    private final CraftStandardService craftStandardService;
    private final StyleService styleService;
    private final MaterialService materialService;
    private final ColorService colorService;
    private final SupplierService supplierService;
    private final AttachmentService attachmentService;
    private final DocLogService docLogService;
    private final UserService userService;
    private final FileUrlSigner signer;
    private final Clock clock;

    // ---------------------------------------------------------------- 查

    @Override
    public TechPackOverviewVO overview(long styleId) {
        PackOwner owner = packOwners.style(styleId);
        List<TechPack> packs = techPackMapper.selectList(new LambdaQueryWrapper<TechPack>().eq(TechPack::getStyleId, styleId));
        packs.sort(Comparator.comparing(TechPack::getStage).thenComparing(TechPack::getVersion, Comparator.reverseOrder()));

        TechPackOverviewVO vo = new TechPackOverviewVO();
        vo.setStyleId(styleId);
        vo.setPacks(briefs(packs));
        vo.setDefaultPackId(Stream.of(find(packs, PackStage.PROD, DRAFT), find(packs, PackStage.PROD, EFFECTIVE),
                        find(packs, PackStage.DEV, DRAFT), find(packs, PackStage.DEV, EFFECTIVE))
                .filter(Objects::nonNull).findFirst().map(TechPack::getId)
                .orElse(packs.isEmpty() ? null : packs.getFirst().getId()));
        vo.setCreatableStages(owner.locked() || !LoginUser.hasAuthority("techpack:pack:add") ? List.of()
                : Stream.of(PackStage.values()).filter(stage -> find(packs, stage, DRAFT) == null).toList());

        TechPack prod = find(packs, PackStage.PROD, EFFECTIVE);
        Ctx ctx = packOwners.context(owner);
        vo.setUnmatchedColors(prod == null ? List.of() : unmatchedColors(packContent.loadBom(prod.getId()), ctx.colors()));
        TechPack costed = prod != null ? prod : find(packs, PackStage.DEV, EFFECTIVE);
        if (costed != null && LoginUser.hasAuthority(COST_PERM)) {
            vo.setUnitCost(costCalculator.unitCost(costed, ctx));
            vo.setUnitCostPackId(costed.getId());
            vo.setUnitCostStage(costed.getStage());
        }
        return vo;
    }

    @Override
    public TechPackDetailVO detail(long id) {
        TechPack pack = getEntity(id);
        Ctx ctx = packOwners.context(packOwners.of(pack));
        Content content = packContent.load(id);

        TechPackDetailVO vo = new TechPackDetailVO();
        vo.setId(pack.getId());
        vo.setStyleId(pack.getStyleId());
        vo.setStyleCode(ctx.owner().code());
        vo.setStyleName(ctx.owner().name());
        vo.setAliasName(ctx.owner().aliasName());
        vo.setStage(pack.getStage());
        vo.setVersion(pack.getVersion());
        vo.setStatus(pack.getStatus());
        vo.setVersionNote(pack.getVersionNote());
        vo.setSourcePackId(pack.getSourcePackId());
        vo.setSourceVersion(at(sourceVersions(List.of(pack)), pack.getSourcePackId()));
        vo.setUtilPct(pack.getUtilPct());
        vo.setPackingMode(pack.getPackingMode());
        vo.setPublishBy(pack.getPublishBy());
        Map<Long, String> names = userService.nicknames(Stream.of(pack.getPublishBy(), pack.getCreateBy())
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        vo.setPublishByName(at(names, pack.getPublishBy()));
        vo.setPublishTime(pack.getPublishTime());
        vo.setCreateByName(at(names, pack.getCreateBy()));
        vo.setCreateTime(pack.getCreateTime());
        vo.setUpdateTime(pack.getUpdateTime());
        vo.setVersionLock(pack.getVersionLock());
        vo.setSizes(ctx.sizes());
        vo.setBaseSize(ctx.baseSize());
        vo.setColors(ctx.colors());
        vo.setPointOptions(craftStandardService.pointOptions());

        vo.setSpecPoints(content.specs().stream().map(TechPackServiceImpl::specVO).toList());
        vo.setBomItems(bomVOs(content.bom(), ctx));
        vo.setCraftSections(craftSections(id, content.texts()));
        vo.setProcesses(content.processes().stream().map(this::processVO).toList());
        vo.setStdSecondsTotal(content.processes().stream().map(p -> p.process().getStdSeconds()).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).sum());
        vo.setLaborTotal(CostCalculator.laborTotal(content.processes().stream().map(PackContent.ProcessRow::process).toList()));
        if (LoginUser.hasAuthority(COST_PERM)) {
            vo.setUnitCost(costCalculator.unitCost(pack, ctx));
        }
        vo.setActions(actions(pack, ctx.owner()));
        return vo;
    }

    @Override
    public List<DocLogVO> logs(long id) {
        checkVisible(id);
        return docLogService.list(BizType.TECH_PACK, id);
    }

    @Override
    public void checkVisible(long id) {
        getEntity(id);
    }

    // ---------------------------------------------------------------- 建、改、删

    @Override
    @Transactional
    public long create(long styleId, PackStage stage) {
        PackOwner owner = packOwners.style(styleId);
        rejectLocked(owner);
        rejectDraftExists(styleId, stage);
        TechPack pack = newDraft(styleId, stage, null);
        pack.setPackingMode(craftStandardService.defaultPackingMode());
        insert(pack);
        List<CraftText> texts = new ArrayList<>();
        craftStandardService.defaultTexts().forEach((section, text) -> {
            CraftText t = new CraftText();
            t.setSection(section);
            t.setContent(text);
            texts.add(t);
        });
        packContent.write(pack.getId(), new Content(List.of(), List.of(), texts, List.of(), List.of()), true);
        docLogService.add(BizType.TECH_PACK, pack.getId(), LOG_CREATE, null, DRAFT, null);
        return pack.getId();
    }

    @Override
    @Transactional
    public int save(long id, TechPackSaveReq req) {
        TechPack pack = getEntity(id);
        FLOW.next("edit", pack.getStatus());
        Ctx ctx = packOwners.context(packOwners.of(pack));
        rejectLocked(ctx.owner());
        Content content = packForms.toContent(req, ctx.sizes());
        boolean withCost = req.getCost() != null;

        // 单头先改（比较并交换 + 乐观锁），拿到这一行的锁，明细再删了重写：两个人同时保存，后到的在这里等，然后 0 行、1002
        LambdaUpdateWrapper<TechPack> update = new LambdaUpdateWrapper<TechPack>()
                .set(TechPack::getVersionNote, PackForms.blankToNull(req.getVersionNote()))
                .set(TechPack::getUtilPct, req.getUtilPct())
                .set(TechPack::getPackingMode, PackForms.blankToNull(req.getPackingMode()))
                .setSql("version_lock = version_lock + 1")
                .eq(TechPack::getId, id)
                .eq(TechPack::getStatus, DRAFT)
                .eq(TechPack::getVersionLock, req.getVersionLock());
        if (withCost) {
            update.set(TechPack::getCostMode, req.getCost().getCostMode() == null
                            ? CostMode.OUTSOURCE : req.getCost().getCostMode())
                    .set(TechPack::getBuyPrice, req.getCost().getBuyPrice());
        }
        if (techPackMapper.update(new TechPack(), update) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这一版已经被别人改过或者生效了，请刷新后再改");
        }
        packContent.clear(id, withCost);
        packContent.write(id, content, withCost);
        Map<CraftSection, CraftSectionForm> sections = new EnumMap<>(CraftSection.class);
        req.getCraftSections().forEach(s -> sections.put(s.getSection(), s));
        for (CraftSection section : CraftSection.values()) {
            CraftSectionForm form = sections.get(section);
            attachmentService.replace(BizType.TECH_PACK, id, section.name(),
                    form == null || form.getImages() == null ? List.of() : form.getImages());
        }
        docLogService.add(BizType.TECH_PACK, id, LOG_UPDATE, null);
        return req.getVersionLock() + 1;
    }

    @Override
    @Transactional
    public void delete(long id) {
        TechPack pack = lock(id);
        FLOW.next("remove", pack.getStatus());
        packContent.clear(id, true);
        attachmentService.deleteByDoc(BizType.TECH_PACK, id);
        docLogService.deleteByDoc(BizType.TECH_PACK, id);
        techPackMapper.deleteById(id);
    }

    // ---------------------------------------------------------------- 动作

    @Override
    @Transactional
    public void publish(long id, int versionLock) {
        TechPack pack = lock(id);
        PackStatus to = FLOW.next("publish", pack.getStatus());
        if (!Objects.equals(pack.getVersionLock(), versionLock)) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这一版刚被别人改过，请刷新后再生效");
        }
        Ctx ctx = packOwners.context(packOwners.of(pack));
        rejectLocked(ctx.owner());
        List<BomRow> bom = packContent.loadBom(id);
        List<PublishProblem> problems = PublishChecks.check(pack, ctx, bom, materials(bom), skus(bom));
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCodes.BIZ_ERROR, pack.getStage().versionName(pack.getVersion()) + " 还不能生效：有 "
                    + problems.size() + " 处要改", problems);
        }

        // 先把同阶段旧的已生效版作废，再让这一版生效：一个阶段只能有一个已生效（uk_tp_pack_effective）
        TechPack old = techPackMapper.selectOne(new LambdaQueryWrapper<TechPack>().eq(TechPack::getStyleId, pack.getStyleId())
                .eq(TechPack::getStage, pack.getStage()).eq(TechPack::getStatus, EFFECTIVE));
        if (old != null) {
            StatusCas.update(techPackMapper, new TechPack(), StatusCas.of(TechPack::getId, old.getId(), TechPack::getStatus,
                    EFFECTIVE, FLOW.next("void", old.getStatus())).setSql("version_lock = version_lock + 1"));
            docLogService.add(BizType.TECH_PACK, old.getId(), LOG_VOID, EFFECTIVE, VOIDED,
                    pack.getStage().versionName(pack.getVersion()) + " 生效，这一版作废");
        }
        StatusCas.update(techPackMapper, new TechPack(), StatusCas.of(TechPack::getId, id, TechPack::getStatus, DRAFT, to)
                .set(TechPack::getPublishBy, LoginUser.currentUserId())
                .set(TechPack::getPublishTime, OffsetDateTime.now(clock))
                .setSql("version_lock = version_lock + 1")
                .eq(TechPack::getVersionLock, versionLock));
        docLogService.add(BizType.TECH_PACK, id, LOG_PUBLISH, DRAFT, to, null);
        styleService.updateTechPackStatus(pack.getStyleId(),
                pack.getStage() == PackStage.PROD || hasEffective(pack.getStyleId(), PackStage.PROD) ? TechPackStatus.PROD : TechPackStatus.DEV);
    }

    @Override
    @Transactional
    public long revise(long id) {
        TechPack source = lock(id);
        FLOW.next("revise", source.getStatus());
        return copy(source, source.getStage(), source.getSourcePackId(), LOG_REVISE,
                "从 " + source.getStage().versionName(source.getVersion()) + " 修订");
    }

    @Override
    @Transactional
    public long toProd(long id) {
        TechPack source = lock(id);
        FLOW.next("toProd", source.getStatus());
        if (source.getStage() != PackStage.DEV) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "只有研发 BOM 能转生产 BOM，请刷新后再试");
        }
        return copy(source, PackStage.PROD, source.getId(), LOG_TO_PROD,
                "从 " + source.getStage().versionName(source.getVersion()) + " 转来");
    }

    @Override
    public List<BomDefaultVO> bomDefaults(long id, List<BomDefaultReq> items) {
        return bomPricing.defaults(getEntity(id).getStage(), items);
    }

    // ---------------------------------------------------------------- 给需求订单

    @Override
    public Map<Long, PackBrief> effectiveProd(Collection<Long> styleIds) {
        List<Long> ids = styleIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        List<TechPack> packs = techPackMapper.selectList(new LambdaQueryWrapper<TechPack>()
                .in(TechPack::getStyleId, ids)
                .eq(TechPack::getStage, PackStage.PROD)
                .eq(TechPack::getStatus, EFFECTIVE));
        Map<Long, PackBrief> byStyle = new HashMap<>();
        briefsOf(packs).values().forEach(b -> byStyle.put(b.styleId(), b));
        return byStyle;
    }

    @Override
    public Map<Long, PackBrief> briefs(Collection<Long> packIds) {
        List<Long> ids = packIds.stream().filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? new HashMap<>() : briefsOf(techPackMapper.selectByIds(ids));
    }

    @Override
    public Map<Long, BigDecimal> laborTotals(Collection<Long> packIds) {
        List<Long> ids = packIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PackProcess>> byPack = packProcessMapper.selectList(new LambdaQueryWrapper<PackProcess>()
                        .in(PackProcess::getTechPackId, ids))
                .stream().collect(Collectors.groupingBy(PackProcess::getTechPackId));
        Map<Long, BigDecimal> out = new HashMap<>();
        techPackMapper.selectByIds(ids).forEach(p -> out.put(p.getId(),
                CostCalculator.laborTotal(byPack.getOrDefault(p.getId(), List.of()))));
        return out;
    }

    /** 每一版按款色配料的行都配了的款色 = 各行配了的款色取交集；没有这种行是 null（哪个颜色都行） */
    private Map<Long, PackBrief> briefsOf(List<TechPack> packs) {
        Map<Long, PackBrief> briefs = new HashMap<>();
        if (packs.isEmpty()) {
            return briefs;
        }
        Map<Long, Map<Long, Set<Long>>> rows = new HashMap<>();
        for (BomItemSkuMapper.ColorRow r : bomItemSkuMapper.selectColorRows(packs.stream().map(TechPack::getId).toList())) {
            Set<Long> colors = rows.computeIfAbsent(r.techPackId(), k -> new HashMap<>())
                    .computeIfAbsent(r.bomItemId(), k -> new HashSet<>());
            if (r.colorId() != null) {
                colors.add(r.colorId());
            }
        }
        for (TechPack p : packs) {
            Set<Long> covered = null;
            for (Set<Long> colors : rows.getOrDefault(p.getId(), Map.of()).values()) {
                if (covered == null) {
                    covered = new HashSet<>(colors);
                } else {
                    covered.retainAll(colors);
                }
            }
            briefs.put(p.getId(), new PackBrief(p.getId(), p.getStyleId(), p.getStage(), p.getVersion(), p.getStatus(),
                    covered == null ? null : Set.copyOf(covered)));
        }
        return briefs;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 修订、转生产 BOM：复制成 stage 的下一版草稿。四个页签、核价的手填部分、工艺指示的插图都带过去，本版说明清空；
     * 转生产 BOM 的单价按生产 BOM 的规则重新取
     */
    private long copy(TechPack source, PackStage stage, Long sourcePackId, String logAction, String remark) {
        PackOwner owner = packOwners.of(source);
        rejectLocked(owner);
        rejectDraftExists(source.getStyleId(), stage);
        TechPack pack = newDraft(source.getStyleId(), stage, sourcePackId);
        pack.setUtilPct(source.getUtilPct());
        pack.setPackingMode(source.getPackingMode());
        pack.setCostMode(source.getCostMode());
        pack.setBuyPrice(source.getBuyPrice());
        insert(pack);
        Content content = packContent.load(source.getId());
        if (stage == PackStage.PROD && source.getStage() == PackStage.DEV) {
            bomPricing.repriceForProd(content.bom());
        }
        packContent.write(pack.getId(), content, true);
        attachmentService.copy(BizType.TECH_PACK, source.getId(), pack.getId());
        docLogService.add(BizType.TECH_PACK, pack.getId(), logAction, null, DRAFT, remark);
        return pack.getId();
    }

    private TechPack newDraft(long styleId, PackStage stage, Long sourcePackId) {
        TechPack pack = new TechPack();
        pack.setStyleId(styleId);
        pack.setStage(stage);
        pack.setVersion(techPackMapper.maxVersion(styleId, stage) + 1);
        pack.setStatus(DRAFT);
        pack.setSourcePackId(sourcePackId);
        pack.setCostMode(CostMode.OUTSOURCE);
        return pack;
    }

    /** 两个人同时建同一阶段的草稿：唯一索引拦下后到的（一个阶段一个草稿、版本号不重） */
    private void insert(TechPack pack) {
        try {
            techPackMapper.insert(pack);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "别人刚建了这个阶段的草稿，请刷新后再试");
        }
    }

    private void rejectDraftExists(long styleId, PackStage stage) {
        TechPack draft = techPackMapper.selectOne(new LambdaQueryWrapper<TechPack>().eq(TechPack::getStyleId, styleId)
                .eq(TechPack::getStage, stage).eq(TechPack::getStatus, DRAFT));
        if (draft != null) {
            throw new BizException(stage.getLabel() + " 已经有草稿 " + stage.versionName(draft.getVersion()) + "，改那一版就行");
        }
    }

    private static void rejectLocked(PackOwner owner) {
        if (owner.locked()) {
            throw new BizException(owner.lockedReason());
        }
    }

    private boolean hasEffective(long styleId, PackStage stage) {
        return techPackMapper.exists(new LambdaQueryWrapper<TechPack>().eq(TechPack::getStyleId, styleId)
                .eq(TechPack::getStage, stage).eq(TechPack::getStatus, EFFECTIVE));
    }

    /** 按钮 = 状态允许的 ∩ 有权限的 ∩ 业务条件（修订、转生产 BOM 要款式没停用、目标阶段没有草稿） */
    private List<String> actions(TechPack pack, PackOwner owner) {
        List<String> actions = new ArrayList<>();
        for (String action : FLOW.actions(pack.getStatus())) {
            String perm = ACTION_PERMS.get(action);
            if (perm == null || !LoginUser.hasAuthority(perm)) {
                continue;
            }
            boolean ok = switch (action) {
                case "revise" -> !owner.locked() && !draftExists(pack.getStyleId(), pack.getStage());
                case "toProd" -> pack.getStage() == PackStage.DEV && !owner.locked() && !draftExists(pack.getStyleId(), PackStage.PROD);
                default -> true;
            };
            if (ok) {
                actions.add(action);
            }
        }
        return actions;
    }

    private boolean draftExists(long styleId, PackStage stage) {
        return techPackMapper.exists(new LambdaQueryWrapper<TechPack>().eq(TechPack::getStyleId, styleId)
                .eq(TechPack::getStage, stage).eq(TechPack::getStatus, DRAFT));
    }

    private List<TechPackBriefVO> briefs(List<TechPack> packs) {
        Map<Long, Integer> sources = sourceVersions(packs);
        Map<Long, String> names = userService.nicknames(packs.stream().flatMap(p -> Stream.of(p.getPublishBy(), p.getCreateBy()))
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return packs.stream().map(p -> {
            TechPackBriefVO b = new TechPackBriefVO();
            b.setId(p.getId());
            b.setStage(p.getStage());
            b.setVersion(p.getVersion());
            b.setStatus(p.getStatus());
            b.setVersionNote(p.getVersionNote());
            b.setSourcePackId(p.getSourcePackId());
            b.setSourceVersion(at(sources, p.getSourcePackId()));
            b.setPublishByName(at(names, p.getPublishBy()));
            b.setPublishTime(p.getPublishTime());
            b.setCreateByName(at(names, p.getCreateBy()));
            b.setCreateTime(p.getCreateTime());
            b.setUpdateTime(p.getUpdateTime());
            return b;
        }).toList();
    }

    /** 来源版本的版本号：来源 id → 版本号 */
    private Map<Long, Integer> sourceVersions(Collection<TechPack> packs) {
        Set<Long> ids = packs.stream().map(TechPack::getSourcePackId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return techPackMapper.selectByIds(ids).stream().collect(Collectors.toMap(TechPack::getId, TechPack::getVersion));
    }

    /** 已生效的生产 BOM 里按款色配料的行，有哪些款色一行都没配（款式后来加的颜色） */
    static List<ColorOption> unmatchedColors(List<BomRow> bom, List<ColorOption> colors) {
        List<BomRow> byColor = bom.stream().filter(r -> r.item().getSkuMode().isByColor()).toList();
        if (byColor.isEmpty()) {
            return List.of();
        }
        return colors.stream().filter(c -> byColor.stream()
                .anyMatch(r -> r.skus().stream().noneMatch(s -> Objects.equals(s.getColorId(), c.id())))).toList();
    }

    private static TechPack find(List<TechPack> packs, PackStage stage, PackStatus status) {
        return packs.stream().filter(p -> p.getStage() == stage && p.getStatus() == status).findFirst().orElse(null);
    }

    private static SpecPointVO specVO(PackContent.SpecRow row) {
        SpecPointVO vo = new SpecPointVO();
        vo.setId(row.point().getId());
        vo.setSeq(row.point().getSeq());
        vo.setPoint(row.point().getPoint());
        vo.setMethod(row.point().getMethod());
        vo.setToleranceCm(row.point().getToleranceCm());
        vo.setGradeCm(row.point().getGradeCm());
        vo.setRemark(row.point().getRemark());
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        row.values().forEach(v -> values.put(v.getSizeCode(), v.getValueCm()));
        vo.setValues(values);
        return vo;
    }

    private List<BomItemVO> bomVOs(List<BomRow> bom, Ctx ctx) {
        if (bom.isEmpty()) {
            return List.of();
        }
        Map<Long, MaterialRef> materials = materials(bom);
        Map<Long, MaterialSkuRef> skus = skus(bom);
        Map<Long, Color> colors = colorService.byIds(bom.stream().flatMap(r -> r.skus().stream()).map(BomItemSku::getColorId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Set<Long> supplierIds = bom.stream().map(r -> r.item().getSupplierId()).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SupplierRef> suppliers = supplierIds.isEmpty() ? Map.of() : supplierService.refs(supplierIds);
        Map<Long, AttachmentVO> materialImages = attachmentService.firsts(BizType.MATERIAL, materials.keySet(), Attachment.IMAGE);
        Map<Long, AttachmentVO> skuImages = attachmentService.firsts(BizType.MATERIAL_SKU, skus.keySet(), Attachment.IMAGE);

        List<BomItemVO> rows = new ArrayList<>();
        for (BomRow row : bom) {
            var item = row.item();
            MaterialRef m = materials.get(item.getMaterialId());
            BomItemVO vo = new BomItemVO();
            vo.setId(item.getId());
            vo.setSeq(item.getSeq());
            vo.setPart(item.getPart());
            vo.setMaterialId(item.getMaterialId());
            if (m != null) {
                vo.setMaterialCode(m.code());
                vo.setMaterialName(m.name());
                vo.setMaterialType(m.materialType());
                vo.setMaterialStatus(m.status());
            }
            vo.setSkuMode(item.getSkuMode());
            vo.setUsageMode(item.getUsageMode());
            vo.setUsageQty(item.getUsageQty());
            Map<String, BigDecimal> sizeUsages = new LinkedHashMap<>();
            row.sizes().forEach(s -> sizeUsages.put(s.getSizeCode(), s.getUsageQty()));
            vo.setSizeUsages(sizeUsages);
            vo.setUnit(item.getUnit());
            vo.setLossPct(item.getLossPct());
            vo.setSource(item.getSource());
            vo.setSupplierId(item.getSupplierId());
            SupplierRef supplier = item.getSupplierId() == null ? null : suppliers.get(item.getSupplierId());
            vo.setSupplierName(supplier == null ? null : supplier.shortName());
            vo.setRefPrice(item.getRefPrice());
            vo.setPriceSourceId(item.getPriceSourceId());
            vo.setPriceTaxIncluded(item.getPriceTaxIncluded());
            vo.setPriceFreightIncluded(item.getPriceFreightIncluded());
            vo.setRemark(item.getRemark());
            vo.setSkus(row.skus().stream().map(s -> skuVO(s, skus.get(s.getMaterialSkuId()), at(colors, s.getColorId()),
                    skuImages.get(s.getMaterialSkuId()))).toList());
            vo.setUnitCost(CostCalculator.rowUnitCost(row, ctx.firstColorId(), ctx.baseSize()));
            BomItemSku first = CostCalculator.pick(row, ctx.firstColorId(), ctx.baseSize());
            AttachmentVO image = first == null ? null : skuImages.get(first.getMaterialSkuId());
            vo.setImage(image != null ? image : materialImages.get(item.getMaterialId()));
            rows.add(vo);
        }
        return rows;
    }

    private static BomSkuVO skuVO(BomItemSku s, MaterialSkuRef sku, Color color, AttachmentVO image) {
        BomSkuVO vo = new BomSkuVO();
        vo.setColorId(s.getColorId());
        if (color != null) {
            vo.setColorCode(color.getCode());
            vo.setColorName(color.getName());
            vo.setColorHex(color.getHex());
        }
        vo.setSizeCode(s.getSizeCode());
        vo.setMaterialSkuId(s.getMaterialSkuId());
        if (sku != null) {
            vo.setSkuCode(sku.code());
            vo.setSkuColorName(sku.colorName());
            vo.setSkuSpec(sku.spec());
            vo.setSkuStatus(sku.status());
        }
        vo.setRefPrice(s.getRefPrice());
        vo.setImage(image);
        return vo;
    }

    /** 九段都出，没写的 content 是 ''；插图挂在 biz_attachment（TECH_PACK，category = 段代码） */
    private List<CraftSectionVO> craftSections(long packId, List<CraftText> texts) {
        Map<CraftSection, String> byCode = texts.stream().collect(Collectors.toMap(CraftText::getSection, CraftText::getContent));
        List<CraftSectionVO> sections = new ArrayList<>();
        for (CraftSection section : CraftSection.values()) {
            sections.add(new CraftSectionVO(section, byCode.getOrDefault(section, ""),
                    attachmentService.list(BizType.TECH_PACK, packId, section.name())));
        }
        return sections;
    }

    private ProcessVO processVO(PackContent.ProcessRow row) {
        var p = row.process();
        ProcessVO vo = new ProcessVO();
        vo.setId(p.getId());
        vo.setSeq(p.getSeq());
        vo.setCategory(p.getCategory());
        vo.setName(p.getName());
        vo.setRequirement(p.getRequirement());
        vo.setStdSeconds(p.getStdSeconds());
        vo.setPrice(p.getPrice());
        vo.setRemark(p.getRemark());
        vo.setImages(row.images().stream().map(i -> new FileVO(signer.sign(i.getFileUrl()), signer.sign(i.getThumbUrl()),
                i.getFileUrl().substring(i.getFileUrl().lastIndexOf('/') + 1), 0)).toList());
        return vo;
    }

    private Map<Long, MaterialRef> materials(List<BomRow> bom) {
        Set<Long> ids = bom.stream().map(r -> r.item().getMaterialId()).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : CostCalculator.byId(materialService.materialRefs(ids), MaterialRef::id);
    }

    private Map<Long, MaterialSkuRef> skus(List<BomRow> bom) {
        Set<Long> ids = bom.stream().flatMap(r -> r.skus().stream()).map(BomItemSku::getMaterialSkuId).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : CostCalculator.byId(materialService.skuRefs(ids), MaterialSkuRef::id);
    }

    /** map.get，key 是 null 时返回 null：Map.of() 这种不可变 map 拿 null 去查会抛空指针（草稿没有生效人、固定配料没有颜色） */
    static <V> V at(Map<Long, V> map, Long key) {
        return key == null ? null : map.get(key);
    }

    private TechPack getEntity(long id) {
        TechPack pack = techPackMapper.selectById(id);
        if (pack == null) {
            throw new BizException(404, NOT_FOUND);
        }
        return pack;
    }

    /** 锁住这一版再动（删、生效、复制）：和保存、别的动作排队 */
    private TechPack lock(long id) {
        TechPack pack = techPackMapper.lockById(id);
        if (pack == null) {
            throw new BizException(404, NOT_FOUND);
        }
        return pack;
    }
}
