package com.lingmao.scm.module.techpack.service;

import com.lingmao.scm.common.excel.ExcelPreview;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.style.dto.StyleCreateReq;
import com.lingmao.scm.module.style.dto.StyleCreateResult;
import com.lingmao.scm.module.techpack.dto.BomDefaultReq;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import com.lingmao.scm.module.techpack.dto.BomImportVO;
import com.lingmao.scm.module.techpack.dto.PackBrief;
import com.lingmao.scm.module.techpack.dto.PackDraftVO;
import com.lingmao.scm.module.techpack.dto.PackFileVO;
import com.lingmao.scm.module.techpack.dto.PackRequirement;
import com.lingmao.scm.module.techpack.dto.TechPackDetailVO;
import com.lingmao.scm.module.techpack.dto.TechPackOverviewVO;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 资料包（docs/api/01、PRD §6.4、架构 §4.7 §4.9）：一个款式一个阶段的一个版本，草稿整版保存，生效后只读，要改就修订出新草稿。
 * 状态规则只写在 TechPackServiceImpl.FLOW 里；保存、生效核对 versionLock，改状态比较并交换，每个动作写一条操作记录（TECH_PACK）。
 * 款式那边的东西（颜色、尺码组、吊牌价、默认加工厂、能不能改）一律从 PackOwners 取（RFC 007 §6.2），二期挂样衣只改那一处。
 * <p>
 * 核价、按款看成本在 TechPackCostService。工艺单是上传的文件（RFC 011），挂在版本上，跟着修订走
 */
public interface TechPackService {

    /** 一个款的版本列表和概况。款式不存在 404 */
    TechPackOverviewVO overview(long styleId);

    /** 一个版本：单头 + 物料清单 + 工艺单文件。不存在 404 */
    TechPackDetailVO detail(long id);

    /** 建下一版草稿（生产 BOM），返回 id：版本号 = 这个款最大的 + 1，内容都是空的。已经有草稿、款式停用 → 1000 */
    long create(long styleId);

    /** 保存草稿，整版覆盖（物料清单、工艺单文件、核价的手填部分），返回新的 versionLock。不是草稿了、versionLock 对不上 → 1002；格式不对 → 1000（说是第几行） */
    int save(long id, TechPackSaveReq req);

    /** 删草稿（连同明细、文件的引用、操作记录）。不是草稿 → 1002 */
    void delete(long id);

    /**
     * 生效：检查没通过 → 1000，data 是 PublishProblem 清单，一次列全。成功后旧的已生效版变已作废，回写款式的资料包状态。
     * versionLock 对不上、不是草稿 → 1002；款式停用 → 1000
     */
    void publish(long id, int versionLock);

    /** 修订：已生效版复制成下一版草稿，返回新 id。不是已生效 → 1002；已有草稿、款式停用 → 1000 */
    long revise(long id);

    /**
     * 复制成新款（RFC 012 §5.4，老款翻新换尺码号型只能换款号）：照 req 建新款（页面上从老款带过来、尺码组可以换、款号重新发），
     * 老款有已生效的资料包就复制成新款的 V1 草稿，新尺码组里没有的尺码的配料、用量去掉，新尺码要补。一个事务
     */
    StyleCreateResult copyStyle(long fromStyleId, StyleCreateReq req);

    /** 几行 BOM 的默认值，按请求的顺序。物料停用了 → 1000 */
    List<BomDefaultVO> bomDefaults(long id, List<BomDefaultReq> items);

    /**
     * 从甲方的 BOM 表认出物料清单（RFC 011 §4）：只解析、不写库，写库走 save。只有草稿能调（和保存一样的报错）。
     * sheet 指定用第几张（从 0 起），null = 按款号、款名自动对；picks：Excel 行号 → 人工挑的物料 id。
     * 文件打不开、认不出表头 → 1000
     */
    BomImportVO bomImport(long id, InputStream file, Integer sheet, Map<Integer, Long> picks);

    /** 工艺单页签上一个 Excel 文件的表格数据（RFC 011 §2.3）。不是这一版的文件 404；不是 xls、xlsx、打不开 → 1000 */
    ExcelPreview.Book preview(long id, long fileId);

    /** 一个版本的工艺单文件（生产单详情上发工厂用，RFC 011 §2.4）。不存在 404 */
    List<PackFileVO> files(long id);

    /** 操作记录，新的在前 */
    List<DocLogVO> logs(long id);

    /** 这一版在不在（款式没有数据范围，看得到资料包就看得到款式）：不在 404 */
    void checkVisible(long id);

    // ---------- 给需求订单 ----------

    /** 几个款现在生效的生产 BOM：款式 id → PackBrief；没有的款不在 map 里 */
    Map<Long, PackBrief> effectiveProd(Collection<Long> styleIds);

    /** 没生效的草稿，按品类线，旧的在前（首页概况、待我审核，docs/api/06）；total 是一共几个 */
    PageResult<PackDraftVO> drafts(PageQuery page);

    /** 几个版本：id → PackBrief（订单锁定的、变更单换的）；查不到的不在 map 里 */
    Map<Long, PackBrief> briefs(Collection<Long> packIds);

    /**
     * 新订单提交、审核用：按实际下单的款式 SKU 查锁定的生产 BOM 配齐没有，用到的款式颜色 SKU、物料、物料 SKU 都不能停用。
     * 有问题 1000，data 是清单，一次列全
     */
    void checkRequirements(Collection<PackRequirement> requirements);

    /** 已审核订单的算料、变更单用：只查配料、用量配齐没有（缺了会算错），下单以后才停用的款式颜色、物料不拦 */
    void checkLockedRequirements(Collection<PackRequirement> requirements);

    /**
     * 几个版本资料包上填的加工费（元 / 件，核价按外发算的口径）：排单时外发加工的单价旁边提示用。
     * 查不到、没有的不在 map 里
     */
    Map<Long, BigDecimal> laborPrices(Collection<Long> packIds);
}
