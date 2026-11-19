package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.techpack.dto.PackFileForm;
import com.lingmao.scm.module.techpack.dto.PackFileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资料包工艺单页签的文件（RFC 011 §2）：挂在 biz_attachment（TECH_PACK，doc_id = 资料包 id），category 分三组。
 * 保存草稿时按组整组替换（AttachmentService.replace），每组能挂的类型不一样，按存下来的文件的扩展名认；
 * 修订时跟着 AttachmentService.copy 带到新版本，文件本身不复制
 */
@Component
@RequiredArgsConstructor
class TechPackFiles {

    static final String CRAFT_SHEET = "CRAFT_SHEET";
    static final String PATTERN = "PATTERN";
    static final String BOM_SOURCE = "BOM_SOURCE";

    private static final Set<String> SHEET_TYPES = Set.of("xls", "xlsx", "pdf", "jpg", "jpeg", "png", "webp");
    /** 组 → 中文名、能挂的扩展名；顺序就是页面上的顺序 */
    private static final Map<String, Group> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put(CRAFT_SHEET, new Group("工艺单", SHEET_TYPES));
        Set<String> pattern = new java.util.HashSet<>(SHEET_TYPES);
        pattern.add("prj");
        GROUPS.put(PATTERN, new Group("纸样和其他", Set.copyOf(pattern)));
        GROUPS.put(BOM_SOURCE, new Group("导入的 BOM 表", Set.of("xls", "xlsx")));
    }

    /** 每组最多挂几个 */
    static final int MAX_PER_GROUP = 20;

    private record Group(String label, Set<String> types) {
    }

    private final AttachmentService attachmentService;

    /** 三组按顺序拼成一个列表 */
    List<PackFileVO> list(long packId) {
        List<PackFileVO> files = new ArrayList<>();
        for (String category : GROUPS.keySet()) {
            for (AttachmentVO a : attachmentService.list(BizType.TECH_PACK, packId, category)) {
                files.add(new PackFileVO(a.id(), category, a.url(), a.thumbUrl(), a.name(), a.size()));
            }
        }
        return files;
    }

    /** 一个文件；不是这一版的 404 */
    PackFileVO get(long packId, long fileId) {
        return list(packId).stream().filter(f -> f.id() == fileId).findFirst()
                .orElseThrow(() -> new BizException(404, "这个文件不在这一版资料包上了，刷新看看"));
    }

    /** 按组整组替换；files 是 null 不动。组不认识 → 400，类型不对、太多 → 1000，原来的都不动 */
    void replace(long packId, List<PackFileForm> files) {
        if (files == null) {
            return;
        }
        Map<String, List<AttachmentReq>> groups = new LinkedHashMap<>();
        GROUPS.keySet().forEach(k -> groups.put(k, new ArrayList<>()));
        for (PackFileForm f : files) {
            Group group = GROUPS.get(f.getCategory());
            if (group == null) {
                throw new BizException(400, "文件分组「" + f.getCategory() + "」不认识");
            }
            String ext = extension(f.getUrl());
            if (!group.types().contains(ext)) {
                throw new BizException(group.label() + "不收 ." + ext + " 文件：" + f.getName());
            }
            groups.get(f.getCategory()).add(f);
        }
        groups.forEach((category, list) -> {
            if (list.size() > MAX_PER_GROUP) {
                throw new BizException(GROUPS.get(category).label() + "最多 " + MAX_PER_GROUP + " 个文件");
            }
        });
        groups.forEach((category, list) -> attachmentService.replace(BizType.TECH_PACK, packId, category, list));
    }

    /** 地址里文件名的扩展名，小写；签名参数去掉 */
    static String extension(String url) {
        String path = url == null ? "" : url.replaceFirst("[?#].*$", "");
        String ext = StringUtils.getFilenameExtension(path.substring(path.lastIndexOf('/') + 1));
        return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
    }
}
