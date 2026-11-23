package com.lingmao.scm.module.techpack.service;

import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.techpack.dto.BomDefaultReq;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import com.lingmao.scm.module.techpack.dto.PackBrief;
import com.lingmao.scm.module.techpack.dto.PackRequirement;
import com.lingmao.scm.module.techpack.dto.TechPackDetailVO;
import com.lingmao.scm.module.techpack.dto.TechPackOverviewVO;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import com.lingmao.scm.module.techpack.entity.PackStage;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 资料包（docs/api/01、PRD §6.4、架构 §4.7 §4.9）：一个款式一个阶段的一个版本，草稿整版保存，生效后只读，要改就修订出新草稿。
 * 状态规则只写在 TechPackServiceImpl.FLOW 里；保存、生效核对 versionLock，改状态比较并交换，每个动作写一条操作记录（TECH_PACK）。
 * 款式那边的东西（颜色、尺码组、吊牌价、默认加工厂、能不能改）一律从 PackOwners 取（RFC 007 §6.2），二期挂样衣只改那一处。
 * <p>
 * 核价、按款看成本在 TechPackCostService；工艺标准在 CraftStandardService；导出 PDF 在 TechPackPdfService
 */
public interface TechPackService {
    record SampleEffectiveHead(Long id,int version) { }
    /** 样衣 owner 的版本条：普通 owner 授权后只加载有界轻量头。 */
    TechPackOverviewVO sampleOverview(long companyId,long sampleId,Integer beforeVersion,int size);
    long createForSample(long companyId,long sampleId);
    /** dev 已授权真实父头后的内部完整性/转换入口；不对外单独暴露。 */
    boolean hasSamplePacks(long companyId,long sampleId);
    SampleEffectiveHead sampleEffectiveHead(long companyId,long sampleId);
    Long adoptSamplePacks(long companyId,long sampleId,long styleId);


    /** 一个款的版本列表和概况。款式不存在 404 */
    TechPackOverviewVO overview(long styleId);

    /** 一个版本：单头 + 尺寸指示、物料清单、工艺指示。不存在 404 */
    TechPackDetailVO detail(long id);

    /**
     * 建这个阶段的下一版草稿，返回 id：版本号 = 这个阶段最大的 + 1，工艺指示带工艺标准的默认文字，包装方式带默认的。
     * 这个阶段已经有草稿、款式停用 → 1000
     */
    long create(long styleId, PackStage stage);

    /** 保存草稿，整版覆盖，返回新的 versionLock。不是草稿了、versionLock 对不上 → 1002；格式不对 → 1000（说是第几行） */
    int save(long id, TechPackSaveReq req);

    /** 删草稿（连同明细、插图、操作记录）。不是草稿 → 1002 */
    void delete(long id);

    /**
     * 生效：检查没通过 → 1000，data 是 PublishProblem 清单，一次列全。成功后同阶段旧的已生效版变已作废，回写款式的资料包状态。
     * versionLock 对不上、不是草稿 → 1002；款式停用 → 1000
     */
    void publish(long id, int versionLock);

    /** 修订：已生效版复制成同阶段的下一版草稿，返回新 id。不是已生效 → 1002；同阶段有草稿、款式停用 → 1000 */
    long revise(long id);

    /** 转生产 BOM：研发已生效版复制成生产阶段的下一版草稿，单价按生产 BOM 的规则重新取，返回新 id */
    long toProd(long id);

    /** 几行 BOM 的默认值，按请求的顺序。物料停用了 → 1000 */
    List<BomDefaultVO> bomDefaults(long id, List<BomDefaultReq> items);

    /** 操作记录，新的在前 */
    List<DocLogVO> logs(long id);

    /** 这一版在不在（款式没有数据范围，看得到资料包就看得到款式）：不在 404 */
    void checkVisible(long id);

    // ---------- 给需求订单 ----------

    /** 几个款现在生效的生产 BOM：款式 id → PackBrief；没有的款不在 map 里 */
    Map<Long, PackBrief> effectiveProd(Collection<Long> styleIds);

    /** 几个版本：id → PackBrief（订单锁定的、变更单换的）；查不到的不在 map 里 */
    Map<Long, PackBrief> briefs(Collection<Long> packIds);

    /**
     * 新订单提交、审核用：按实际下单的款式 SKU 查锁定的生产 BOM 配齐没有，用到的款式颜色 SKU、物料、物料 SKU 都不能停用。
     * 有问题 1000，data 是清单，一次列全
     */
    void checkRequirements(Collection<PackRequirement> requirements);
    /** 同一套校验的只读结果；批量加载 BOM，按资料包/SKU 归属返回，避免评审逐款回源。 */
    Map<com.lingmao.scm.module.techpack.dto.PackRequirement, List<String>> requirementProblems(Collection<com.lingmao.scm.module.techpack.dto.PackRequirement> requirements);

    /** 已审核订单的算料、变更单用：只查配料、用量配齐没有（缺了会算错），下单以后才停用的款式颜色、物料不拦 */
    void checkLockedRequirements(Collection<PackRequirement> requirements);

    /** 几个版本的工序工价合计（元 / 件，核价的"加工费"口径）：排单时外发加工的单价旁边提示用。查不到的不在 map 里 */
    Map<Long, BigDecimal> laborTotals(Collection<Long> packIds);
    /** 资料包草稿的有界查询，按创建时间及 ID 从早到晚排列。 */
    com.lingmao.scm.common.page.PageResult<com.lingmao.scm.module.techpack.dto.PackDraftVO> drafts(com.lingmao.scm.common.page.PageQuery page);
}
