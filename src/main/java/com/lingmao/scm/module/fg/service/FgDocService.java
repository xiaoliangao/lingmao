package com.lingmao.scm.module.fg.service;

import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.fg.dto.FgDocCreateReq;
import com.lingmao.scm.module.fg.dto.FgDocCreatedVO;
import com.lingmao.scm.module.fg.dto.FgDocDetailVO;
import com.lingmao.scm.module.fg.dto.FgDocQuery;
import com.lingmao.scm.module.fg.dto.FgDocRowVO;
import com.lingmao.scm.module.fg.dto.FgDocSaveReq;
import com.lingmao.scm.module.fg.dto.FgFromReceiptReq;
import com.lingmao.scm.module.fg.dto.FgPendingReceiptQuery;
import com.lingmao.scm.module.fg.dto.FgPendingReceiptRowVO;
import com.lingmao.scm.module.fg.dto.FgReceiptDraftVO;
import com.lingmao.scm.module.fg.dto.FgReceiptStockedVO;
import com.lingmao.scm.module.fg.dto.FgShipOrderOptionVO;
import com.lingmao.scm.module.fg.dto.FgShipPrefillVO;
import com.lingmao.scm.module.fg.dto.FgShippedRow;
import com.lingmao.scm.module.fg.dto.FgStyleOptionVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;

/** 成品入库单、出库单（docs/api/07 §3.2–§3.4、§3.7） */
public interface FgDocService {

    PageResult<FgDocRowVO> page(FgDocQuery query);

    FgDocDetailVO detail(long id);

    /** 公共接口（操作记录、附件）用：不存在、不在数据范围里 404 */
    void checkVisible(long id);

    /** 其他入库、发货出库、其他出库 */
    FgDocCreatedVO create(FgDocCreateReq req);

    /** 改草稿，返回新的 version */
    int update(long id, FgDocSaveReq req);

    /** 删没过过账的草稿 */
    void delete(long id);

    /** audit 审核过账、unaudit 反审核、void 作废过过账的草稿 */
    void act(long id, String action, DocActionReq req);

    PageResult<FgPendingReceiptRowVO> pendingReceipts(FgPendingReceiptQuery query);

    /** 待入库的回货单张数（首页"回货待入成品仓"） */
    long pendingReceiptCount();

    FgReceiptDraftVO receiptDraft(long receiptId);

    FgDocCreatedVO fromReceipt(long receiptId, FgFromReceiptReq req);

    List<FgShipOrderOptionVO> shipOrders(String keyword, Long warehouseId);

    FgShipPrefillVO shipPrefill(long orderId, Long warehouseId, Long docId);

    List<FgStyleOptionVO> styleOptions(List<Long> styleIds);

    byte[] itemsTemplate();

    /** 其他入库的草稿导入明细（有一行不对整批不导，1003） */
    ImportResult importItems(long id, MultipartFile file);

    /** 订单详情的"发 n"：看不见这张订单 404 */
    List<FgShippedRow> shipped(long orderId);

    /** 生产单回货页签的"已入库" */
    List<FgReceiptStockedVO> byReceipts(Collection<Long> receiptIds);

    byte[] export(FgDocQuery query);
}
