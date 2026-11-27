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
import com.lingmao.scm.common.exception.BizException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * categories 空 = 不按分类筛；给几个就列分类里有其中任一个的（款式的默认加工厂：成衣厂或加工厂）。
     * usage 不传按 SAMPLE（待准入、审批中、合作中都能选）；BULK 只要合作中。
     * includeId：表单里已经选着的那一家，不满足条件也带上（停用了照样显示它的名字）。
     * 品类线（RFC 013 §2）：只列和我的线有交集的；productLine 给了只列做这条线的（采购单、生产单选供应商）
     */
    List<SupplierOption> options(List<String> categories, Usage usage, String keyword, Long includeId, String productLine);

    /** 单据、价格档案显示和带默认值用：编码、全称、简称、状态、税率、结算方式、账期、发票类型。不存在抛 404 */
    SupplierRef getRef(long id);

    /** 批量版：价格档案、单据的列表一次取一页的供应商名称。不存在的 id 不在返回里（列表只是显示，不报错） */
    Map<Long, SupplierRef> refs(Collection<Long> ids);

    /**
     * 按名字找供应商（资料包从 BOM 表导入，RFC 011 §4.2）：简称或全称去掉首尾空格后完全相同，不分大小写。
     * key 是去掉首尾空格、转小写的名字 → 对得上的几家，按编码排；对不上的名字不在 map 里
     */
    Map<String, List<SupplierRef>> refsByNames(Collection<String> names);

    /**
     * 能不能选进单据（PRD §6.8.2 的表）。SAMPLE：打样、录价格；BULK：采购单、生产单。
     * 不能用抛 1000"××现在是「停用」，不能……"。不存在、品类线和我的没有交集抛 404
     */
    void checkUsable(long id, Usage usage);

    /** 供应商做不做这条品类线（RFC 013 §2）：采购单、生产单的线要在供应商的线里。做是 null，不做是原因；不存在抛 404 */
    String lineProblem(long id, String productLine);

    /** 供应商做哪几条品类线（字典 product_line 的 value），没有是空列表；不存在抛 404。对账按它判断谁能对这家的账（RFC 019 §5.1） */
    List<String> productLines(long id);

    /** 不做这条品类线抛 1000，见 lineProblem */
    default void checkServes(long id, String productLine) {
        String problem = lineProblem(id, productLine);
        if (problem != null) {
            throw new BizException(problem);
        }
    }

    /** 几家供应商的分类（字典 supplier_category 的 value）：id → 分类；没有分类的是空列表，查不到的不在 map 里 */
    Map<Long, List<String>> categories(Collection<Long> ids);

    /** 会建工厂仓、能接外发加工单和成品采购单的两类（PRD §6.1.6）：成衣厂、加工厂，字典内置项 */
    Set<String> FACTORY_CATEGORIES = Set.of("GARMENT", "PROCESSOR");

    /** 分类里有成衣厂或加工厂 */
    static boolean isFactory(Collection<String> categories) {
        return categories.stream().anyMatch(FACTORY_CATEGORIES::contains);
    }

    /** 选供应商是干什么用（PRD §6.8.2）：打样、录价格要求松一点，大货单据只要合作中的 */
    enum Usage {
        /** 打样、录价格：待准入、准入审批中、合作中 */
        SAMPLE,
        /** 采购单、生产单：只要合作中 */
        BULK
    }
}
