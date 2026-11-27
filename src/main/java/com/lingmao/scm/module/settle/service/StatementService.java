package com.lingmao.scm.module.settle.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.settle.dto.CompareNotesReq;
import com.lingmao.scm.module.settle.dto.CompareResult;
import com.lingmao.scm.module.settle.dto.PendingStatementQuery;
import com.lingmao.scm.module.settle.dto.PendingStatementRow;
import com.lingmao.scm.module.settle.dto.StatementCreateReq;
import com.lingmao.scm.module.settle.dto.StatementCreatedVO;
import com.lingmao.scm.module.settle.dto.StatementDeductionReq;
import com.lingmao.scm.module.settle.dto.StatementDetailVO;
import com.lingmao.scm.module.settle.dto.StatementItemsReq;
import com.lingmao.scm.module.settle.dto.StatementQuery;
import com.lingmao.scm.module.settle.dto.StatementRowVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 供应商对账单（RFC 019 §5.1、§5.3，docs/api/09 §2） */
public interface StatementService {

    PageResult<StatementRowVO> page(StatementQuery query);

    /** 待对账：按主体 × 供应商 × 月份合计还没进对账单的单据（不按单据的制单人限，最多 500 组） */
    List<PendingStatementRow> pending(PendingStatementQuery query);

    StatementDetailVO detail(long id);

    /** 不存在、不在数据范围里 → 404 */
    void checkVisible(long id);

    /** 生成：这段时间里这家还没对过的单据拉进来；一张都没有 → 1000 */
    StatementCreatedVO create(StatementCreateReq req);

    /** 草稿刷新：后来审核的单据加进来 */
    StatementCreatedVO refresh(long id, int version);

    /** 草稿移出几行，返回新的 version */
    int removeItems(long id, StatementItemsReq req);

    /** 草稿上加一笔扣款（同时登记），返回新的 version */
    int addDeduction(long id, StatementDeductionReq req);

    /** 状态动作：send 发送 / withdraw 撤回 / confirm 确认 / void 作废 */
    void act(long id, String action, DocActionReq req);

    /** 上传对方的 Excel 对账单比对，结果存在对账单上 */
    CompareResult compare(long id, int version, MultipartFile file);

    /** 写差异说明，返回新的 version */
    int saveNotes(long id, CompareNotesReq req);

    /** 对方对账单的原件（最近一次比对传的） */
    List<AttachmentVO> theirFiles(long id);

    /** 导出成 Excel 发给对方 */
    byte[] export(long id);

    /** 导出的文件名：对账单号 + 供应商 */
    String exportName(long id);
}
