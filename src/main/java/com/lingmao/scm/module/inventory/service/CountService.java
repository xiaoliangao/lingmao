package com.lingmao.scm.module.inventory.service;

import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.inventory.dto.CountAddItemsReq;
import com.lingmao.scm.module.inventory.dto.CountCreateReq;
import com.lingmao.scm.module.inventory.dto.CountCreatedVO;
import com.lingmao.scm.module.inventory.dto.CountDetailVO;
import com.lingmao.scm.module.inventory.dto.CountOpenWarehouseVO;
import com.lingmao.scm.module.inventory.dto.CountQuery;
import com.lingmao.scm.module.inventory.dto.CountRowVO;
import com.lingmao.scm.module.inventory.dto.CountSaveReq;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 物料盘点单（docs/api/04 §3.5）：草稿 → 待审核 → 已审核（审核即过账，按差异调库存）；过过账又反审核回来的草稿只能作废 */
public interface CountService {

    PageResult<CountRowVO> page(CountQuery query);

    CountDetailVO detail(long id);

    List<CountOpenWarehouseVO> openWarehouses();

    CountCreatedVO create(CountCreateReq req);

    /** 存草稿；返回新的 version */
    int update(long id, CountSaveReq req);

    CountDetailVO addItems(long id, CountAddItemsReq req);

    CountDetailVO refreshBook(long id, DocActionReq req);

    /** submit、withdraw、reject、audit、unaudit、void */
    void act(long id, String action, DocActionReq req);

    void delete(long id);

    /** 盘点表（Excel），也是导回实盘数的模板 */
    byte[] sheet(long id);

    ImportResult importSheet(long id, MultipartFile file);

    byte[] export(CountQuery query);

    String no(long id);

    void checkVisible(long id);

    /** 改附件前查：在本人数据范围里，不放"审核不受数据范围限制"（只能审，不能改）；不在 → 404 */
    void checkEditable(long id);
}
