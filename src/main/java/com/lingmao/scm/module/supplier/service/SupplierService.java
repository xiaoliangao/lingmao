package com.lingmao.scm.module.supplier.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.supplier.dto.BankVO;
import com.lingmao.scm.module.supplier.dto.SupplierOption;
import com.lingmao.scm.module.supplier.dto.SupplierQuery;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.dto.SupplierRowVO;
import com.lingmao.scm.module.supplier.dto.SupplierSaveReq;
import com.lingmao.scm.module.supplier.dto.SupplierSaveResult;
import com.lingmao.scm.module.supplier.dto.SupplierVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 供应商档案和状态动作（RFC 004）。关键字段改动的审批在 SupplierChangeService，资质在 SupplierCertService，
 * 导入导出在 SupplierExcelService。
 * <p>
 * 状态和动作照 RFC 004 §2 的转换表（SupplierServiceImpl.FLOW），改状态一律比较并交换，每个动作写一条操作记录。
 * 当前状态做不了的动作返回 1002（按钮是按打开时的状态显示的，刷新就好）；业务上不允许的（缺资料、自己批自己的）返回 1000。
 * <p>
 * 给别的域用的是最后两个方法：getRef、checkUsable（RFC 004 §7）。
 */
public interface SupplierService {

    /** 列表，条件见 SupplierQuery。不出账号 */
    PageResult<SupplierRowVO> page(SupplierQuery query);

    /** 详情：账号遮成前 4 后 4。不存在 404 */
    SupplierVO detail(long id);

    /** 银行信息全号：要 bank 权限（Controller 拦），每调一次记一条操作日志（Controller 的 @OperLog） */
    BankVO bank(long id);

    /** 新建，状态是待准入，编码取 GYS + 4 位流水 */
    SupplierSaveResult create(SupplierSaveReq req);

    /**
     * 保存（RFC 004 §4）：普通字段直接改；关键字段待准入时直接改，准入过的挂起一份改动等审批（信用代码从空补上的直接改）。
     * 准入审批中不能改（1000）；已经有一份待批的改动时又改了关键字段（1000）
     */
    SupplierSaveResult update(long id, SupplierSaveReq req);

    /** 删除（RFC 004 §10）：审批中、黑名单的不能删（1000）；被价格档案、物料、单据引用的外键拦住（1005） */
    void delete(long id);

    /** 申请准入：缺的资料一次列全，1000 + 缺项清单（data 是 List&lt;String&gt;） */
    void submit(long id);

    /** 撤回准入申请：只有申请人能撤 */
    void withdraw(long id);

    /** 准入通过：不能是申请人（超级管理员也一样） */
    void audit(long id);

    /** 驳回准入申请，回待准入：不能是申请人 */
    void reject(long id, String reason);

    void disable(long id);

    void enable(long id);

    void blacklist(long id, String reason);

    /** 解除黑名单：准入过的回停用，从没准入过的回待准入（RFC 004 §2） */
    void unblacklist(long id, String reason);

    /** 操作记录，新的在前 */
    List<DocLogVO> logs(long id);

    /**
     * 下拉（SupplierSelect）：按分类、按能不能用筛，关键字查编码、全称、简称，按编码排，最多 50 条。
     * usage 不传按 SAMPLE（待准入、审批中、合作中都能选）；BULK 只要合作中。
     * includeId：表单里已经选着的那一家，不满足条件也带上（停用了照样显示它的名字）
     */
    List<SupplierOption> options(String category, Usage usage, String keyword, Long includeId);

    /** 单据、价格档案显示和带默认值用：编码、全称、简称、状态、税率、结算方式、账期、发票类型。不存在抛 404 */
    SupplierRef getRef(long id);

    /** 批量版：价格档案、单据的列表一次取一页的供应商名称。不存在的 id 不在返回里（列表只是显示，不报错） */
    Map<Long, SupplierRef> refs(Collection<Long> ids);

    /**
     * 能不能选进单据（PRD §6.8.2 的表）。SAMPLE：打样、录价格；BULK：采购单、生产单。
     * 不能用抛 1000"××现在是「停用」，不能……"。不存在抛 404
     */
    void checkUsable(long id, Usage usage);

    /** 选供应商是干什么用（PRD §6.8.2）：打样、录价格要求松一点，大货单据只要合作中的 */
    enum Usage {
        /** 打样、录价格：待准入、准入审批中、合作中 */
        SAMPLE,
        /** 采购单、生产单：只要合作中 */
        BULK
    }
}
