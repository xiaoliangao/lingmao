package com.lingmao.scm.module.techpack.service;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.techpack.dto.CraftStandardVO;
import com.lingmao.scm.module.techpack.dto.CraftTextsReq;
import com.lingmao.scm.module.techpack.dto.PointOption;
import com.lingmao.scm.module.techpack.dto.SizePointMethodsReq;
import com.lingmao.scm.module.techpack.entity.CraftSection;

import java.util.List;
import java.util.Map;

/**
 * 工艺标准（PRD §6.9.2、架构 §4.9，资料包页签上拉出的抽屉）：存在三处，不新建表——
 * 各段默认文字和默认包装方式在 sys_config，部位的默认测量方法在字典 size_point 的 text_value，文件在 biz_attachment（CRAFT_STANDARD，id 0）。
 * 改了只影响以后新建的资料包
 */
public interface CraftStandardService {

    CraftStandardVO get();

    /** 文件整组替换：顺序就是导出时附页的顺序。只收 PDF 和 jpg、png，别的 400 */
    List<AttachmentVO> replaceFiles(List<AttachmentReq> files);

    /** 九段默认文字、默认包装方式，整份覆盖。包装方式不是启用的字典值 → 1000 */
    void saveTexts(CraftTextsReq req);

    /** 改几个部位的默认测量方法 */
    void saveMethods(List<SizePointMethodsReq.Item> points);

    /** 加一个部位：进字典 size_point，value 就用名称。同名的已经有了 → 1000 */
    void addPoint(String label, String method);

    // ---------- 给资料包 ----------

    /** 新建草稿时抄的默认文字：有字的段，段 → 文字 */
    Map<CraftSection, String> defaultTexts();

    /** 新建草稿时默认的包装方式；没设是 null */
    String defaultPackingMode();

    /** 尺寸指示选部位的下拉：启用的部位和默认测量方法 */
    List<PointOption> pointOptions();

    /** 工艺标准文件（导出 PDF 附页用），按顺序 */
    List<AttachmentVO> files();
}
