package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.entity.DictData;
import com.lingmao.scm.module.system.service.ConfigService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.techpack.dto.CraftStandardVO;
import com.lingmao.scm.module.techpack.dto.CraftTextsReq;
import com.lingmao.scm.module.techpack.dto.PointOption;
import com.lingmao.scm.module.techpack.dto.SizePointMethodsReq;
import com.lingmao.scm.module.techpack.entity.CraftSection;
import com.lingmao.scm.module.techpack.service.CraftStandardService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CraftStandardServiceImpl implements CraftStandardService {

    /** sys_config 的键：{段代码: 文字}，只存有字的段 */
    static final String TEXTS_KEY = "techpack.craft_defaults";
    /** sys_config 的键：字典 packing_mode 的值 */
    static final String PACKING_KEY = "techpack.packing_mode";
    static final String SIZE_POINT = "size_point";
    static final String PACKING_MODE = "packing_mode";
    /** 工艺标准文件挂在 biz_attachment 的 CRAFT_STANDARD 下，id 固定 0（架构 §4.9） */
    static final long FILES_DOC_ID = 0L;

    /** 附页能拼进 PDF 的：PDF 直接合并，图片一张一页（架构 §11.1） */
    private static final Set<String> FILE_TYPES = Set.of("pdf", "jpg", "jpeg", "png");

    private final ConfigService configService;
    private final DictDataService dictDataService;
    private final AttachmentService attachmentService;

    @Override
    public CraftStandardVO get() {
        Map<CraftSection, String> stored = defaultTexts();
        Map<CraftSection, String> texts = new EnumMap<>(CraftSection.class);
        for (CraftSection section : CraftSection.values()) {
            texts.put(section, stored.getOrDefault(section, ""));
        }
        List<CraftStandardVO.SizePointStd> points = dictDataService.listWithText(SIZE_POINT).stream()
                .map(d -> new CraftStandardVO.SizePointStd(d.getValue(), d.getLabel(), d.getTextValue(), d.getStatus()))
                .toList();
        return new CraftStandardVO(files(), texts, defaultPackingMode(), points,
                LoginUser.hasAuthority("techpack:standard:edit"));
    }

    @Override
    @Transactional
    public List<AttachmentVO> replaceFiles(List<AttachmentReq> files) {
        for (AttachmentReq file : files) {
            String path = file.getUrl() == null ? "" : file.getUrl().replaceAll("[?#].*$", "");
            String ext = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (!FILE_TYPES.contains(ext)) {
                throw new BizException(400, "工艺标准文件只收 PDF 和 jpg、png 图片：" + file.getName());
            }
        }
        return attachmentService.replace(BizType.CRAFT_STANDARD, FILES_DOC_ID, Attachment.FILE, files);
    }

    @Override
    @Transactional
    public void saveTexts(CraftTextsReq req) {
        String packingMode = StringUtils.hasText(req.getPackingMode()) ? req.getPackingMode().strip() : null;
        if (packingMode != null && dictDataService.listByType(PACKING_MODE).stream()
                .noneMatch(o -> o.enabled() && o.value().equals(packingMode))) {
            throw new BizException("包装方式「" + packingMode + "」不在字典里，或者已经停用了");
        }
        Map<String, String> texts = new LinkedHashMap<>();
        for (CraftSection section : CraftSection.values()) {
            String text = req.getTexts().get(section);
            if (StringUtils.hasText(text)) {
                texts.put(section.name(), text.strip());
            }
        }
        configService.put(TEXTS_KEY, texts);
        configService.put(PACKING_KEY, packingMode);
    }

    @Override
    @Transactional
    public void saveMethods(List<SizePointMethodsReq.Item> points) {
        Map<String, String> texts = new LinkedHashMap<>();
        points.forEach(p -> texts.put(p.getValue(), p.getMethod()));
        dictDataService.updateTextValues(SIZE_POINT, texts);
    }

    @Override
    @Transactional
    public void addPoint(String label, String method) {
        String name = label.strip();
        List<DictData> existing = dictDataService.listWithText(SIZE_POINT);
        DictData same = existing.stream()
                .filter(d -> d.getLabel().equalsIgnoreCase(name) || d.getValue().equalsIgnoreCase(name))
                .findFirst().orElse(null);
        if (same != null) {
            throw new BizException(Objects.equals(same.getStatus(), 1)
                    ? "部位「" + name + "」已经有了"
                    : "部位「" + name + "」已经有了，是停用的：到管理后台的字典管理里启用它");
        }
        DictDataSaveReq req = new DictDataSaveReq();
        req.setTypeCode(SIZE_POINT);
        req.setLabel(name);
        req.setValue(name);
        req.setSort(existing.stream().mapToInt(d -> d.getSort() == null ? 0 : d.getSort()).max().orElse(0) + 1);
        req.setStatus(1);
        dictDataService.create(req);
        if (StringUtils.hasText(method)) {
            dictDataService.updateTextValues(SIZE_POINT, Map.of(name, method));
        }
    }

    // ---------- 给资料包 ----------

    @Override
    @SuppressWarnings("unchecked")
    public Map<CraftSection, String> defaultTexts() {
        Map<String, Object> stored = configService.get(TEXTS_KEY, Map.class).orElse(Map.of());
        Map<CraftSection, String> texts = new EnumMap<>(CraftSection.class);
        for (CraftSection section : CraftSection.values()) {
            Object text = stored.get(section.name());
            if (text instanceof String s && StringUtils.hasText(s)) {
                texts.put(section, s);
            }
        }
        return texts;
    }

    @Override
    public String defaultPackingMode() {
        String mode = configService.get(PACKING_KEY, String.class).orElse(null);
        // 设完以后字典项被停用了：新建的资料包不再带它
        return mode != null && dictDataService.listByType(PACKING_MODE).stream()
                .anyMatch(o -> o.enabled() && o.value().equals(mode)) ? mode : null;
    }

    @Override
    public List<PointOption> pointOptions() {
        return dictDataService.listWithText(SIZE_POINT).stream()
                .filter(d -> Objects.equals(d.getStatus(), 1))
                .map(d -> new PointOption(d.getLabel(), d.getTextValue()))
                .toList();
    }

    @Override
    public List<AttachmentVO> files() {
        return attachmentService.list(BizType.CRAFT_STANDARD, FILES_DOC_ID, Attachment.FILE);
    }
}
