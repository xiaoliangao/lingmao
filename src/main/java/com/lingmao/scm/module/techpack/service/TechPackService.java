package com.lingmao.scm.module.techpack.service;

import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.techpack.dto.BomDefaultReq;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import com.lingmao.scm.module.techpack.dto.TechPackDetailVO;
import com.lingmao.scm.module.techpack.dto.TechPackOverviewVO;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import com.lingmao.scm.module.techpack.entity.PackStage;

import java.util.List;

/**
 * 资料包（docs/api/01、PRD §6.4、架构 §4.7 §4.9）：一个款式一个阶段的一个版本，草稿整版保存，生效后只读，要改就修订出新草稿。
 * 状态规则只写在 TechPackServiceImpl.FLOW 里；保存、生效核对 versionLock，改状态比较并交换，每个动作写一条操作记录（TECH_PACK）。
 * 款式那边的东西（颜色、尺码组、吊牌价、默认加工厂、能不能改）一律从 PackOwners 取（RFC 007 §6.2），二期挂样衣只改那一处。
 * <p>
 * 核价、按款看成本在 TechPackCostService；工艺标准在 CraftStandardService；导出 PDF 在 TechPackPdfService
 */
public interface TechPackService {

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
}
