package com.lingmao.scm.module.style.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.service.CategoryService;
import com.lingmao.scm.module.basic.service.ColorService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.SizeGroupService;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.style.dto.StyleColorVO;
import com.lingmao.scm.module.style.dto.StyleCreateReq;
import com.lingmao.scm.module.style.dto.StyleCreateResult;
import com.lingmao.scm.module.style.dto.StyleDetailVO;
import com.lingmao.scm.module.style.dto.StyleListVO;
import com.lingmao.scm.module.style.dto.StylePickQuery;
import com.lingmao.scm.module.style.dto.StylePickVO;
import com.lingmao.scm.module.style.dto.StyleQuery;
import com.lingmao.scm.module.style.dto.StylePackInfo;
import com.lingmao.scm.module.style.dto.StyleRef;
import com.lingmao.scm.module.style.dto.StyleSaveReq;
import com.lingmao.scm.module.style.dto.StyleSkuSaveReq;
import com.lingmao.scm.module.style.dto.StyleSkuVO;
import com.lingmao.scm.module.style.entity.Style;
import com.lingmao.scm.module.style.entity.StyleColor;
import com.lingmao.scm.module.style.entity.StyleSku;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.entity.TechPackStatus;
import com.lingmao.scm.module.style.mapper.StyleColorMapper;
import com.lingmao.scm.module.style.mapper.StyleMapper;
import com.lingmao.scm.module.style.mapper.StyleSkuMapper;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lingmao.scm.module.style.entity.StyleStatus.DISABLED;
import static com.lingmao.scm.module.style.entity.StyleStatus.DRAFT;
import static com.lingmao.scm.module.style.entity.StyleStatus.ENABLED;

/**
 * 款式（RFC 005）。状态规则只写在 FLOW 里；改状态一律比较并交换，每个动作写一条操作记录（STYLE）。
 * 加颜色、排颜色、去颜色、生成 SKU、改尺码组、删除先锁款式这一行（StyleMapper.lockById），同一款的这些改动排队做
 */
@Service
@RequiredArgsConstructor
public class StyleServiceImpl implements StyleService {

    /** 启用要有启用的 SKU（不在表里，enable 里查） */
    static final StatusFlow<StyleStatus> FLOW = StatusFlow.of(StyleStatus.class)
            .on("enable", DRAFT, DISABLED).to(ENABLED)
            .on("disable", ENABLED).to(DISABLED)
            .build();

    /** 设计附件在 biz_attachment 里的分组（RFC 005 §2） */
    static final String DESIGN = "DESIGN";

    static final int ENABLED_FLAG = 1;
    static final int DISABLED_FLAG = 0;

    private static final String NOT_FOUND = "款式不存在，可能已经被删除了";

    private final StyleMapper styleMapper;
    private final StyleColorMapper styleColorMapper;
    private final StyleSkuMapper styleSkuMapper;
    private final StyleCodes codes;
    private final StyleDicts dicts;
    private final StyleCategories categories;
    private final SkuGenerator skuGenerator;
    private final StyleExcel styleExcel;
    private final CategoryService categoryService;
    private final ColorService colorService;
    private final SizeGroupService sizeGroupService;
    private final MaterialService materialService;
    private final SupplierService supplierService;
    private final UserService userService;
    private final TradeFeature tradeFeature;
    private final AttachmentService attachmentService;
    private final DocLogService docLogService;

    // ------------------------------------------------------------------ 查

    @Override
    public PageResult<StyleListVO> page(StyleQuery query) {
        query.setKeyword(trim(query.getKeyword()));
        IPage<StyleListVO> page = styleMapper.selectListPage(query.toPage(), query, codesIn(query.getKeyword()),
                categories.withDescendants(query.getCategoryId()));
        List<StyleListVO> rows = page.getRecords();
        List<Long> ids = rows.stream().map(StyleListVO::getId).toList();
        Map<Long, AttachmentVO> images = attachmentService.firsts(BizType.STYLE, ids, Attachment.IMAGE);
        Map<Long, List<ColorOption>> colors = colorChips(ids, false);
        Map<Long, String> designers = userService.nicknames(rows.stream().map(StyleListVO::getDesignerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        for (StyleListVO row : rows) {
            row.setImage(images.get(row.getId()));
            row.setColors(colors.getOrDefault(row.getId(), List.of()));
            row.setDesignerName(nameOf(designers, row.getDesignerId()));
        }
        return PageResult.of(page);
    }

    @Override
    public StyleDetailVO detail(long id) {
        Style s = getEntity(id);
        StyleDetailVO vo = new StyleDetailVO();
        BeanUtils.copyProperties(s, vo);
        StyleCategories.Index index = categories.index();
        vo.setCategoryName(index.name(s.getCategoryId()));
        vo.setCategoryPath(index.path(s.getCategoryId()));
        Map<Long, String> names = userService.nicknames(Stream.of(s.getDesignerId(), s.getPatternMakerId(), s.getCreateBy())
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        vo.setDesignerName(nameOf(names, s.getDesignerId()));
        vo.setPatternMakerName(nameOf(names, s.getPatternMakerId()));
        vo.setCreateByName(nameOf(names, s.getCreateBy()));
        SizeGroupVO sizeGroup = sizeGroupService.getById(s.getSizeGroupId());
        vo.setSizeGroupName(sizeGroup.name());
        vo.setSizes(sizeGroup.sizes());
        if (s.getMainMaterialId() != null) {
            MaterialRef material = materialService.materialRefs(List.of(s.getMainMaterialId())).getFirst();
            vo.setMainMaterialCode(material.code());
            vo.setMainMaterialName(material.name());
        }
        if (s.getDefaultFactoryId() != null) {
            SupplierRef factory = supplierService.getRef(s.getDefaultFactoryId());
            vo.setDefaultFactoryCode(factory.code());
            vo.setDefaultFactoryName(factory.shortName());
        }
        vo.setColors(colorVOs(id));
        vo.setSkuCount(Math.toIntExact(styleSkuMapper.selectCount(new LambdaQueryWrapper<StyleSku>().eq(StyleSku::getStyleId, id))));
        vo.setImages(attachmentService.list(BizType.STYLE, id, Attachment.IMAGE));
        vo.setFiles(attachmentService.list(BizType.STYLE, id, DESIGN));
        vo.setActions(actions(s));
        return vo;
    }

    /** 当前用户能做的 = 转换表允许的 ∩ 有权限的（架构 §4.2） */
    static List<String> actions(Style s) {
        List<String> actions = new ArrayList<>();
        if (StylePerms.has(StylePerms.EDIT)) {
            actions.addAll(FLOW.actions(s.getStatus()));
            actions.add("edit");
        }
        if (StylePerms.has(StylePerms.REMOVE)) {
            actions.add("remove");
        }
        return actions;
    }

    @Override
    public String codePreview(String styleClass, Long designerId) {
        dicts.require(StyleDicts.STYLE_CLASS, trim(styleClass), null, "大类");
        return codes.preview(trim(styleClass), designerId);
    }

    @Override
    public List<DocLogVO> logs(long id) {
        getEntity(id);
        return docLogService.list(BizType.STYLE, id);
    }

    // ------------------------------------------------------------------ 新建、保存、删除

    @Override
    @Transactional
    public StyleCreateResult create(StyleCreateReq req) {
        Style s = new Style();
        fill(s, req, null);
        List<Color> colors = resolveColors(req.getColorIds());
        List<String> sizes = sizeGroupService.getById(s.getSizeGroupId()).sizes();
        // 字段都查过了再取号：前面报错时不碰流水那一行的锁
        String code = req.getCodeMode() == StyleCreateReq.CodeMode.MANUAL
                ? codes.manual(req.getCode())
                : codes.take(s.getStyleClass(), s.getDesignerId());
        s.setCode(code);
        s.setStatus(DRAFT);
        s.setTechPackStatus(TechPackStatus.NONE);
        try {
            styleMapper.insert(s);
        } catch (DuplicateKeyException e) {
            throw new BizException(StyleCodes.DUPLICATE);   // 同一时间别人手填了同一个款号
        }
        insertColors(s.getId(), colors, 1);
        List<String> skus = skuGenerator.generate(s, colors, sizes);
        attachmentService.replace(BizType.STYLE, s.getId(), Attachment.IMAGE, req.getImages());
        attachmentService.replace(BizType.STYLE, s.getId(), DESIGN, req.getFiles());
        docLogService.add(BizType.STYLE, s.getId(), "create", null, DRAFT, skus.isEmpty() ? null : "生成 " + skus.size() + " 个 SKU");
        return new StyleCreateResult(s.getId(), code, skus.size());
    }

    @Override
    @Transactional
    public void update(long id, StyleSaveReq req) {
        lock(id);
        Style s = getEntity(id);
        if (!Objects.equals(trim(req.getStyleClass()), s.getStyleClass())) {
            throw new BizException("大类建好就不能改：款号开头就是大类");
        }
        if (!Objects.equals(req.getSizeGroupId(), s.getSizeGroupId()) && hasSkus(id)) {
            throw new BizException("已生成 SKU，不能换尺码组。还没下过订单的，先在「颜色尺码」里删掉 SKU 再换");
        }
        Style old = new Style();
        BeanUtils.copyProperties(s, old);
        fill(s, req, old);
        styleMapper.update(s, Updates.replaceById(s));
        docLogService.add(BizType.STYLE, id, "update", null);
    }

    @Override
    @Transactional
    public void delete(long id) {
        lock(id);
        getEntity(id);
        // 先删本域的子表，再删挂在款式上的图、记录，最后删款式（RFC 005 §4）。
        // 哪一步被外键拦住（SKU 被需求订单、款式被资料包、价格档案引用了）整个回滚，前面删的都还在
        styleSkuMapper.delete(new LambdaQueryWrapper<StyleSku>().eq(StyleSku::getStyleId, id));
        styleColorMapper.delete(new LambdaQueryWrapper<StyleColor>().eq(StyleColor::getStyleId, id));
        attachmentService.deleteByDoc(BizType.STYLE, id);
        docLogService.deleteByDoc(BizType.STYLE, id);
        styleMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void enable(long id) {
        Style s = getEntity(id);
        StyleStatus to = FLOW.next("enable", s.getStatus());
        boolean hasEnabledSku = styleSkuMapper.exists(new LambdaQueryWrapper<StyleSku>()
                .eq(StyleSku::getStyleId, id).eq(StyleSku::getStatus, ENABLED_FLAG));
        if (!hasEnabledSku) {
            throw new BizException("还没有启用的 SKU，不能启用：先在「颜色尺码」里加颜色、生成 SKU");
        }
        StatusCas.update(styleMapper, new Style(), StatusCas.of(Style::getId, id, Style::getStatus, s.getStatus(), to));
        docLogService.add(BizType.STYLE, id, "enable", s.getStatus(), to, null);
    }

    @Override
    @Transactional
    public void disable(long id) {
        Style s = getEntity(id);
        StyleStatus to = FLOW.next("disable", s.getStatus());
        StatusCas.update(styleMapper, new Style(), StatusCas.of(Style::getId, id, Style::getStatus, s.getStatus(), to));
        docLogService.add(BizType.STYLE, id, "disable", s.getStatus(), to, null);
    }

    // ------------------------------------------------------------------ 颜色

    @Override
    @Transactional
    public void addColors(long id, List<Long> colorIds) {
        lock(id);
        getEntity(id);
        List<StyleColor> existing = styleColors(id);
        Set<Long> have = existing.stream().map(StyleColor::getColorId).collect(Collectors.toSet());
        List<Color> colors = resolveColors(colorIds);
        List<String> dup = colors.stream().filter(c -> have.contains(c.getId())).map(Color::getName).toList();
        if (!dup.isEmpty()) {
            throw new BizException("已经有这个颜色了：" + String.join("、", dup));
        }
        int next = existing.stream().mapToInt(StyleColor::getSeq).max().orElse(0) + 1;
        insertColors(id, colors, next);
        docLogService.add(BizType.STYLE, id, "addColors", colorNames(colors));
    }

    @Override
    @Transactional
    public void reorderColors(long id, List<Long> colorIds) {
        lock(id);
        getEntity(id);
        List<StyleColor> existing = styleColors(id);
        List<Long> order = colorIds.stream().filter(Objects::nonNull).distinct().toList();
        Set<Long> have = existing.stream().map(StyleColor::getColorId).collect(Collectors.toSet());
        if (order.size() != existing.size() || !have.containsAll(order)) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "颜色刚被别人改过，请刷新后再排");
        }
        Map<Long, StyleColor> byColor = existing.stream().collect(Collectors.toMap(StyleColor::getColorId, Function.identity()));
        for (int i = 0; i < order.size(); i++) {
            StyleColor sc = byColor.get(order.get(i));
            if (sc.getSeq() != i + 1) {
                styleColorMapper.update(null, new LambdaUpdateWrapper<StyleColor>()
                        .set(StyleColor::getSeq, i + 1).eq(StyleColor::getId, sc.getId()));
            }
        }
        docLogService.add(BizType.STYLE, id, "sortColors", null);
    }

    @Override
    @Transactional
    public void enableColor(long id, long colorId) {
        setColorStatus(id, colorId, ENABLED_FLAG, "enableColor");
    }

    @Override
    @Transactional
    public void disableColor(long id, long colorId) {
        setColorStatus(id, colorId, DISABLED_FLAG, "disableColor");
    }

    @Override
    @Transactional
    public void removeColor(long id, long colorId) {
        lock(id);
        StyleColor sc = getStyleColor(id, colorId);
        boolean used = styleSkuMapper.exists(new LambdaQueryWrapper<StyleSku>()
                .eq(StyleSku::getStyleId, id).eq(StyleSku::getColorId, colorId));
        if (used) {
            throw new BizException("这个颜色已经生成了 SKU，只能停用。确实要去掉的，先删掉它的 SKU");
        }
        styleColorMapper.deleteById(sc.getId());
        docLogService.add(BizType.STYLE, id, "removeColor", colorNames(List.of(colorService.getById(colorId))));
    }

    // ------------------------------------------------------------------ SKU

    @Override
    public List<StyleSkuVO> skus(long id) {
        Style s = getEntity(id);
        List<StyleSku> skus = styleSkuMapper.selectList(new LambdaQueryWrapper<StyleSku>().eq(StyleSku::getStyleId, id));
        Map<Long, Integer> colorOrder = new HashMap<>();
        styleColors(id).forEach(sc -> colorOrder.put(sc.getColorId(), sc.getSeq()));
        List<String> sizes = sizeGroupService.getById(s.getSizeGroupId()).sizes();
        Map<Long, Color> colors = colorService.byIds(skus.stream().map(StyleSku::getColorId).collect(Collectors.toSet()));
        Comparator<StyleSku> order = Comparator
                .comparing((StyleSku k) -> colorOrder.getOrDefault(k.getColorId(), Integer.MAX_VALUE))
                .thenComparing(k -> indexOrLast(sizes, k.getSizeCode()))
                .thenComparing(StyleSku::getCode);
        return skus.stream().sorted(order).map(k -> {
            Color c = colors.get(k.getColorId());
            return new StyleSkuVO(k.getId(), k.getCode(), k.getColorId(), c == null ? null : c.getCode(),
                    c == null ? null : c.getName(), c == null ? null : c.getHex(), k.getSizeCode(), k.getBarcode(),
                    k.getTagPrice(), k.getStatus());
        }).toList();
    }

    /**
     * 启用的颜色 × 尺码组的全部尺码，已有的跳过（RFC 005 §5）。颜色在基础资料里停用了的也跳过：停用的不能再新用（RFC 003 §2）
     */
    @Override
    @Transactional
    public int generateSkus(long id) {
        lock(id);
        Style s = getEntity(id);
        List<StyleColor> enabled = styleColors(id).stream().filter(sc -> sc.getStatus() == ENABLED_FLAG).toList();
        Map<Long, Color> found = colorService.byIds(enabled.stream().map(StyleColor::getColorId).toList());
        List<Color> colors = enabled.stream().map(sc -> found.get(sc.getColorId()))
                .filter(c -> c != null && c.getStatus() == ENABLED_FLAG).toList();
        if (colors.isEmpty()) {
            throw new BizException("还没有启用的颜色：先加颜色，再生成 SKU");
        }
        List<String> created = skuGenerator.generate(s, colors, sizeGroupService.getById(s.getSizeGroupId()).sizes());
        if (!created.isEmpty()) {
            docLogService.add(BizType.STYLE, id, "generate", codesText(created));
        }
        return created.size();
    }

    @Override
    @Transactional
    public void updateSku(long id, long skuId, StyleSkuSaveReq req) {
        StyleSku sku = getSku(id, skuId);
        String barcode = trim(req.getBarcode());
        if (barcode != null) {
            StyleSku owner = styleSkuMapper.selectOne(new LambdaQueryWrapper<StyleSku>()
                    .eq(StyleSku::getBarcode, barcode).ne(StyleSku::getId, skuId));
            if (owner != null) {
                throw new BizException("条码 " + barcode + " 已经给了 SKU " + owner.getCode());
            }
        }
        sku.setBarcode(barcode);
        sku.setTagPrice(req.getTagPrice());
        try {
            styleSkuMapper.update(sku, Updates.replaceById(sku));
        } catch (DuplicateKeyException e) {
            throw new BizException("条码 " + barcode + " 刚被别的 SKU 用了");
        }
        docLogService.add(BizType.STYLE, id, "updateSku", sku.getCode());
    }

    @Override
    @Transactional
    public void deleteSku(long id, long skuId) {
        StyleSku sku = getSku(id, skuId);
        styleSkuMapper.deleteById(skuId);   // 被需求订单、生产单、库存引用了，外键拦住 → 1005
        docLogService.add(BizType.STYLE, id, "deleteSku", sku.getCode());
    }

    @Override
    @Transactional
    public void enableSku(long id, long skuId) {
        setSkuStatus(id, skuId, ENABLED_FLAG, "enableSku");
    }

    @Override
    @Transactional
    public void disableSku(long id, long skuId) {
        setSkuStatus(id, skuId, DISABLED_FLAG, "disableSku");
    }

    // ------------------------------------------------------------------ 图片、附件

    @Override
    @Transactional
    public List<AttachmentVO> replaceImages(long id, List<AttachmentReq> files) {
        getEntity(id);
        List<AttachmentVO> images = attachmentService.replace(BizType.STYLE, id, Attachment.IMAGE, files);
        docLogService.add(BizType.STYLE, id, "images", images.size() + " 张");
        return images;
    }

    @Override
    @Transactional
    public List<AttachmentVO> replaceFiles(long id, List<AttachmentReq> files) {
        getEntity(id);
        List<AttachmentVO> saved = attachmentService.replace(BizType.STYLE, id, DESIGN, files);
        docLogService.add(BizType.STYLE, id, "files", saved.size() + " 个");
        return saved;
    }

    // ------------------------------------------------------------------ 选款式、导入导出

    @Override
    public PageResult<StylePickVO> pick(StylePickQuery query) {
        query.setKeyword(trim(query.getKeyword()));
        // 品牌按名称搜：字典里名称对得上的品牌 value 交给 SQL（款式上存的是 value）
        List<String> brands = query.getKeyword() == null ? List.of() : dicts.valuesLabelled(StyleDicts.BRAND, query.getKeyword());
        IPage<StylePickVO> page = styleMapper.selectPickPage(query.toPage(), query, categories.withDescendants(query.getCategoryId()), brands);
        List<StylePickVO> rows = page.getRecords();
        List<Long> ids = rows.stream().map(StylePickVO::getId).toList();
        Map<Long, AttachmentVO> images = attachmentService.firsts(BizType.STYLE, ids, Attachment.IMAGE);
        Map<Long, List<ColorOption>> colors = colorChips(ids, true);
        for (StylePickVO row : rows) {
            row.setImage(images.get(row.getId()));
            row.setColors(colors.getOrDefault(row.getId(), List.of()));
        }
        return PageResult.of(page);
    }

    @Override
    public byte[] importTemplate() {
        return styleExcel.template();
    }

    @Override
    public ImportResult importExcel(MultipartFile file) {
        return styleExcel.importFile(file);
    }

    @Override
    public byte[] export(StyleQuery query) {
        query.setKeyword(trim(query.getKeyword()));
        List<Long> ids = styleMapper.selectIds(query, codesIn(query.getKeyword()),
                categories.withDescendants(query.getCategoryId()), Excel.MAX_EXPORT_ROWS + 1);
        if (ids.size() > Excel.MAX_EXPORT_ROWS) {
            throw new BizException("要导出的超过 " + Excel.MAX_EXPORT_ROWS / 10_000 + " 万行了，请缩小筛选范围再导出");
        }
        return styleExcel.export(ids);
    }

    // ------------------------------------------------------------------ 给别的域

    @Override
    public void checkUsable(long id) {
        Style s = getEntity(id);
        if (s.getStatus() == DISABLED) {
            throw new BizException("款式 " + s.getCode() + " 现在是「" + s.getStatus().getLabel() + "」，不能再用");
        }
    }

    @Override
    public Map<Long, StyleRef> refs(Collection<Long> ids) {
        List<Long> distinct = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        // 一律给 HashMap：调用方拿行上可能为空的 styleId（物料价）直接 get，Map.of() 的 get(null) 会抛空指针
        Map<Long, StyleRef> refs = new HashMap<>();
        if (distinct.isEmpty()) {
            return refs;
        }
        styleMapper.selectList(new LambdaQueryWrapper<Style>()
                        .select(Style::getId, Style::getCode, Style::getName)
                        .in(Style::getId, distinct))
                .forEach(s -> refs.put(s.getId(), new StyleRef(s.getId(), s.getCode(), s.getName())));
        return refs;
    }

    @Override
    public StylePackInfo packInfo(long id) {
        StylePackInfo info = packInfos(List.of(id)).get(id);
        if (info == null) {
            throw new BizException(404, NOT_FOUND);
        }
        return info;
    }

    @Override
    public Map<Long, StylePackInfo> packInfos(Collection<Long> ids) {
        List<Long> distinct = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, StylePackInfo> infos = new HashMap<>();
        if (distinct.isEmpty()) {
            return infos;
        }
        Map<Long, List<Long>> colors = styleColorMapper.selectList(new LambdaQueryWrapper<StyleColor>()
                        .in(StyleColor::getStyleId, distinct)
                        .eq(StyleColor::getStatus, ENABLED_FLAG)
                        .orderByAsc(StyleColor::getStyleId, StyleColor::getSeq, StyleColor::getId))
                .stream().collect(Collectors.groupingBy(StyleColor::getStyleId, LinkedHashMap::new,
                        Collectors.mapping(StyleColor::getColorId, Collectors.toList())));
        for (Style s : styleMapper.selectByIds(distinct)) {
            infos.put(s.getId(), new StylePackInfo(s.getId(), s.getCode(), s.getName(), s.getAliasName(), s.getYear(),
                    s.getSeason(), s.getSampleNo(), s.getSizeGroupId(), colors.getOrDefault(s.getId(), List.of()),
                    s.getTagPrice(), s.getDefaultFactoryId(), s.getProductName(), s.getComposition(), s.getStatus()));
        }
        return infos;
    }

    @Override
    public void updateTechPackStatus(long styleId, TechPackStatus status) {
        int updated = styleMapper.update(new Style(), new LambdaUpdateWrapper<Style>()
                .set(Style::getTechPackStatus, status)
                .eq(Style::getId, styleId));
        if (updated == 0) {
            throw new BizException(404, NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 款式自己的列（新建、修改共用，RFC 005 §6）。old 是修改前的（新建传 null）：没改的字典值、品类、尺码组、主力面料，
     * 后来停用了也照样留着；新选的要启用。默认加工厂只当默认值，不查状态（架构 A23）
     */
    private void fill(Style s, StyleSaveReq req, Style old) {
        String source = Objects.requireNonNullElse(trim(req.getSource()), StyleDicts.OWN);
        String customerStyleNo = trim(req.getCustomerStyleNo());
        tradeFeature.rejectIfOff(StyleDicts.CUSTOMER_SAMPLE.equals(source), "来源「客户来样」");
        tradeFeature.rejectIfOff(req.getCustomerId() != null, "客户");
        tradeFeature.rejectIfOff(customerStyleNo != null, "客户款号");

        String styleClass = trim(req.getStyleClass());
        String brand = trim(req.getBrand());
        String season = trim(req.getSeason());
        String wave = trim(req.getWave());
        String styleTag = trim(req.getStyleTag());
        String series = trim(req.getSeries());
        String gender = trim(req.getGender());
        String execStandard = trim(req.getExecStandard());
        String safetyCategory = trim(req.getSafetyCategory());
        boolean creating = old == null;
        dicts.require(StyleDicts.STYLE_CLASS, styleClass, creating ? null : old.getStyleClass(), "大类");
        dicts.require(StyleDicts.BRAND, brand, creating ? null : old.getBrand(), "品牌");
        dicts.require(StyleDicts.SEASON, season, creating ? null : old.getSeason(), "季节");
        dicts.require(StyleDicts.WAVE, wave, creating ? null : old.getWave(), "波段");
        dicts.require(StyleDicts.STYLE_TAG, styleTag, creating ? null : old.getStyleTag(), "风格");
        dicts.require(StyleDicts.SERIES, series, creating ? null : old.getSeries(), "系列");
        dicts.require(StyleDicts.GENDER, gender, creating ? null : old.getGender(), "性别");
        dicts.require(StyleDicts.SOURCE, source, creating ? null : old.getSource(), "来源");
        dicts.require(StyleDicts.EXEC_STANDARD, execStandard, creating ? null : old.getExecStandard(), "执行标准");
        dicts.require(StyleDicts.SAFETY_CATEGORY, safetyCategory, creating ? null : old.getSafetyCategory(), "安全类别");

        if (creating || !Objects.equals(req.getCategoryId(), old.getCategoryId())) {
            categoryService.checkSelectable(req.getCategoryId());
        }
        if (creating || !Objects.equals(req.getSizeGroupId(), old.getSizeGroupId())) {
            SizeGroupVO group = sizeGroupService.getById(req.getSizeGroupId());
            if (group.status() == null || group.status() != ENABLED_FLAG) {
                throw new BizException("尺码组「" + group.name() + "」已经停用了，请换一个");
            }
        }
        if (req.getMainMaterialId() != null && (creating || !Objects.equals(req.getMainMaterialId(), old.getMainMaterialId()))) {
            MaterialRef material = materialService.materialRefs(List.of(req.getMainMaterialId())).getFirst();
            if (material.status() == null || material.status() != ENABLED_FLAG) {
                throw new BizException("主力面料 " + material.code() + " " + material.name() + " 已经停用了，请换一个");
            }
        }

        s.setName(trim(req.getName()));
        s.setAliasName(trim(req.getAliasName()));
        s.setStyleClass(styleClass);
        s.setBrand(brand);
        s.setCategoryId(req.getCategoryId());
        s.setYear(req.getYear());
        s.setSeason(season);
        s.setWave(wave);
        s.setStyleTag(styleTag);
        s.setSeries(series);
        s.setGender(gender);
        s.setDesignerId(req.getDesignerId());
        s.setPatternMakerId(req.getPatternMakerId());
        s.setSource(source);
        s.setCustomerId(req.getCustomerId());
        s.setCustomerStyleNo(customerStyleNo);
        s.setSizeGroupId(req.getSizeGroupId());
        s.setMainMaterialId(req.getMainMaterialId());
        s.setProductName(trim(req.getProductName()));
        s.setComposition(trim(req.getComposition()));
        s.setExecStandard(execStandard);
        s.setSafetyCategory(safetyCategory);
        s.setTagPrice(req.getTagPrice());
        s.setRefCost(req.getRefCost());
        s.setDefaultFactoryId(req.getDefaultFactoryId());
        s.setLaunchDate(req.getLaunchDate());
        s.setRemark(trim(req.getRemark()));
    }

    /** 选的颜色：都要存在、启用；重复的去掉，保持选的顺序 */
    private List<Color> resolveColors(List<Long> colorIds) {
        List<Long> ids = colorIds == null ? List.of() : colorIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Color> found = colorService.byIds(ids);
        List<Color> colors = new ArrayList<>();
        for (Long colorId : ids) {
            Color color = found.get(colorId);
            if (color == null) {
                throw new BizException("选的颜色有一个不存在了，可能刚被删了，请刷新");
            }
            if (color.getStatus() == null || color.getStatus() != ENABLED_FLAG) {
                throw new BizException("颜色 " + color.getCode() + " " + color.getName() + " 已经停用了");
            }
            colors.add(color);
        }
        return colors;
    }

    /** 按给的顺序接在 firstSeq 后面；同一时间别人加了同一个颜色由 uk_sty_style_color 拦住 */
    private void insertColors(long styleId, List<Color> colors, int firstSeq) {
        int seq = firstSeq;
        for (Color color : colors) {
            StyleColor sc = new StyleColor();
            sc.setStyleId(styleId);
            sc.setColorId(color.getId());
            sc.setSeq(seq++);
            sc.setStatus(ENABLED_FLAG);
            try {
                styleColorMapper.insert(sc);
            } catch (DuplicateKeyException e) {
                throw new BizException("已经有这个颜色了：" + color.getName());
            }
        }
    }

    private List<StyleColor> styleColors(long styleId) {
        return styleColorMapper.selectList(new LambdaQueryWrapper<StyleColor>()
                .eq(StyleColor::getStyleId, styleId)
                .orderByAsc(StyleColor::getSeq, StyleColor::getId));
    }

    private List<StyleColorVO> colorVOs(long styleId) {
        List<StyleColor> rows = styleColors(styleId);
        Map<Long, Color> colors = colorService.byIds(rows.stream().map(StyleColor::getColorId).toList());
        Map<Long, Long> skuCounts = styleSkuMapper.selectList(new LambdaQueryWrapper<StyleSku>()
                        .select(StyleSku::getColorId).eq(StyleSku::getStyleId, styleId))
                .stream().collect(Collectors.groupingBy(StyleSku::getColorId, Collectors.counting()));
        return rows.stream().map(sc -> {
            Color c = colors.get(sc.getColorId());
            return new StyleColorVO(sc.getColorId(), c == null ? null : c.getCode(), c == null ? null : c.getName(),
                    c == null ? null : c.getHex(), sc.getSeq(), sc.getStatus(), c == null ? null : c.getStatus(),
                    Math.toIntExact(skuCounts.getOrDefault(sc.getColorId(), 0L)));
        }).toList();
    }

    /** 一批款式的颜色色块，按顺序；onlyEnabled：只要在款式上启用的（选款式弹窗） */
    private Map<Long, List<ColorOption>> colorChips(List<Long> styleIds, boolean onlyEnabled) {
        if (styleIds.isEmpty()) {
            return Map.of();
        }
        List<StyleColor> rows = styleColorMapper.selectList(new LambdaQueryWrapper<StyleColor>()
                .in(StyleColor::getStyleId, styleIds)
                .eq(onlyEnabled, StyleColor::getStatus, ENABLED_FLAG)
                .orderByAsc(StyleColor::getStyleId, StyleColor::getSeq));
        Map<Long, Color> colors = colorService.byIds(rows.stream().map(StyleColor::getColorId).collect(Collectors.toSet()));
        Map<Long, List<ColorOption>> chips = new LinkedHashMap<>();
        for (StyleColor sc : rows) {
            Color c = colors.get(sc.getColorId());
            if (c != null) {
                chips.computeIfAbsent(sc.getStyleId(), k -> new ArrayList<>()).add(ColorOption.from(c));
            }
        }
        return chips;
    }

    private void setColorStatus(long id, long colorId, int status, String action) {
        StyleColor sc = getStyleColor(id, colorId);
        styleColorMapper.update(null, new LambdaUpdateWrapper<StyleColor>().set(StyleColor::getStatus, status).eq(StyleColor::getId, sc.getId()));
        docLogService.add(BizType.STYLE, id, action, colorNames(List.of(colorService.getById(colorId))));
    }

    private void setSkuStatus(long id, long skuId, int status, String action) {
        StyleSku sku = getSku(id, skuId);
        styleSkuMapper.update(new StyleSku(), new LambdaUpdateWrapper<StyleSku>().set(StyleSku::getStatus, status).eq(StyleSku::getId, skuId));
        docLogService.add(BizType.STYLE, id, action, sku.getCode());
    }

    private boolean hasSkus(long styleId) {
        return styleSkuMapper.exists(new LambdaQueryWrapper<StyleSku>().eq(StyleSku::getStyleId, styleId));
    }

    private void lock(long id) {
        if (styleMapper.lockById(id) == null) {
            throw new BizException(404, NOT_FOUND);
        }
    }

    Style getEntity(long id) {
        Style s = styleMapper.selectById(id);
        if (s == null) {
            throw new BizException(404, NOT_FOUND);
        }
        return s;
    }

    /** 颜色要在这个款式上，不然当不存在 */
    private StyleColor getStyleColor(long styleId, long colorId) {
        StyleColor sc = styleColorMapper.selectOne(new LambdaQueryWrapper<StyleColor>()
                .eq(StyleColor::getStyleId, styleId).eq(StyleColor::getColorId, colorId));
        if (sc == null) {
            throw new BizException(404, "这个款式上没有这个颜色，可能刚被去掉了，请刷新");
        }
        return sc;
    }

    /** SKU 要属于这个款式，不然当不存在 */
    private StyleSku getSku(long styleId, long skuId) {
        StyleSku sku = styleSkuMapper.selectById(skuId);
        if (sku == null || !sku.getStyleId().equals(styleId)) {
            throw new BizException(404, "SKU 不存在，可能刚被删了，请刷新");
        }
        return sku;
    }

    private static int indexOrLast(List<String> sizes, String size) {
        int i = sizes.indexOf(size);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private static String colorNames(List<Color> colors) {
        return colors.stream().map(c -> c.getCode() + " " + c.getName()).collect(Collectors.joining("、"));
    }

    private static String codesText(List<String> codes) {
        String text = String.join("、", codes);
        return text.length() <= 400 ? text : text.substring(0, 400) + "…（共 " + codes.size() + " 个）";
    }

    /** 关键字里有空格：拆成几个款号（大写）一起查；没有空格返回 null，按包含查 */
    static List<String> codesIn(String keyword) {
        if (keyword == null || !keyword.matches(".*\\s.*")) {
            return null;
        }
        return new ArrayList<>(Arrays.stream(keyword.split("\\s+"))
                .filter(t -> !t.isEmpty())
                .map(t -> t.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    /** 按 id 取名字；id 为空返回 null（nicknames 空集合时给的是 Map.of()，get(null) 会抛空指针） */
    static String nameOf(Map<Long, String> names, Long id) {
        return id == null ? null : names.get(id);
    }

    static String trim(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
