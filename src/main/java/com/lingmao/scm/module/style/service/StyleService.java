package com.lingmao.scm.module.style.service;

import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.style.dto.StyleCreateReq;
import com.lingmao.scm.module.style.dto.StyleCreateResult;
import com.lingmao.scm.module.style.dto.StyleDetailVO;
import com.lingmao.scm.module.style.dto.StyleListVO;
import com.lingmao.scm.module.style.dto.StylePickQuery;
import com.lingmao.scm.module.style.dto.StylePickVO;
import com.lingmao.scm.module.style.dto.StyleQuery;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.dto.StylePackInfo;
import com.lingmao.scm.module.style.dto.StyleRef;
import com.lingmao.scm.module.style.dto.StyleSaveReq;
import com.lingmao.scm.module.style.dto.StyleSkuSaveReq;
import com.lingmao.scm.module.style.dto.StyleSkuVO;
import com.lingmao.scm.module.style.entity.TechPackStatus;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 款式、颜色、SKU（RFC 005）。款号建好不改；状态照转换表（StyleServiceImpl.FLOW）比较并交换，每个动作写一条操作记录；
 * 颜色只追加；SKU = 启用的颜色 × 尺码组的尺码，一键生成只补缺。
 * <p>
 * 给别的域用的在最后："给别的域"下面。checkUsable、refs 给价格档案；packInfo、packInfos、updateTechPackStatus 给资料包；
 * 需求订单开工时再加 checkOrderable（RFC 005 §4）。
 */
public interface StyleService {

    PageResult<StyleListVO> page(StyleQuery query);

    /** 款式 + 颜色 + 尺码组 + 图片 + 设计附件 + 领猫附加属性 + 能做的动作。不存在 404 */
    StyleDetailVO detail(long id);

    /**
     * 自动编号的预览（RFC 005 §3.1）：只看不取，跳过已经被手填占了的号。
     * 设计师没选、没有设计师编号、今天的流水用完了 → 1000，提示可以手填
     */
    String codePreview(String styleClass, Long designerId);

    /** 新建，状态是草稿。带了颜色的同时生成 SKU。返回实际的款号 */
    StyleCreateResult create(StyleCreateReq req);

    /** 只改款式自己的列，不收款号、状态。已经有 SKU 的换尺码组 → 1000 */
    void update(long id, StyleSaveReq req);

    /** 连同 SKU、颜色、附件、操作记录一起删；被需求订单、资料包、价格档案引用了外键拦住 → 1005，整个回滚 */
    void delete(long id);

    /** 草稿、停用 → 启用：至少有一个启用的 SKU（1000） */
    void enable(long id);

    /** 启用 → 停用 */
    void disable(long id);

    /** 加颜色：追加在后面，要存在、启用；已经有的 → 1000 */
    void addColors(long id, List<Long> colorIds);

    /** 颜色的新顺序：要正好是款式上的全部颜色 */
    void reorderColors(long id, List<Long> colorIds);

    void enableColor(long id, long colorId);

    /** 停用颜色不连带停用它的 SKU（RFC 005 §5） */
    void disableColor(long id, long colorId);

    /** 去掉颜色：生成过 SKU 的只能停用（1000） */
    void removeColor(long id, long colorId);

    /** SKU，按颜色顺序、尺码顺序 */
    List<StyleSkuVO> skus(long id);

    /** 一键生成：启用的颜色 × 尺码组的全部尺码，已有的跳过。返回新建了几个；调几次结果都一样 */
    int generateSkus(long id);

    /** 改条码、吊牌价。条码重复 → 1000 */
    void updateSku(long id, long skuId, StyleSkuSaveReq req);

    /** 被需求订单引用了外键拦住 → 1005 */
    void deleteSku(long id, long skuId);

    void enableSku(long id, long skuId);

    void disableSku(long id, long skuId);

    /** 款式图整组替换，第一张是主图（RFC 003 §2） */
    List<AttachmentVO> replaceImages(long id, List<AttachmentReq> files);

    /** 设计附件整组替换 */
    List<AttachmentVO> replaceFiles(long id, List<AttachmentReq> files);

    /** 操作记录，新的在前 */
    List<DocLogVO> logs(long id);

    /** 选款式（DataPick）：启用的款式 */
    PageResult<StylePickVO> pick(StylePickQuery query);

    byte[] importTemplate();

    /** 导入（RFC 005 §8）：只新增；有一处不对整批不导（1003） */
    ImportResult importExcel(MultipartFile file);

    /** 导出：款式一张表 + SKU 一张表，条件同列表 */
    byte[] export(StyleQuery query);

    // ---------- 给别的域 ----------

    /**
     * 能不能录加工价：没停用就行，草稿也能录（打样阶段工厂就报价，原型只滤掉停用的，RFC 006 §3）。
     * 不存在抛 404；停用抛 1000。以后的单据要"启用"才能选的，到时候加一个用途参数（照 SupplierService.Usage）
     */
    void checkUsable(long id);

    /** 这个款式在不在、当前用户能不能看（品类线，RFC 013 §2）：资料包、按款看成本跟着款式隔开 */
    boolean visible(long id);

    /** 款式的品类线：几个款一起查，订单、生产单按它定自己的线；查不到的不在 map 里 */
    Map<Long, String> productLines(Collection<Long> ids);

    /** 一批款式的 id、款号、名称，给列表显示用。id → StyleRef；查不到的（被删了）不在 map 里；get(null) 返回 null，不抛 */
    Map<Long, StyleRef> refs(Collection<Long> ids);

    /** 资料包用的款式：款号款名、尺码组、启用的颜色、吊牌价、默认加工厂、状态。不存在、不在我的品类线里 404 */
    StylePackInfo packInfo(long id);

    /** 批量版（按款看成本一页几十个款）；查不到的不在 map 里 */
    Map<Long, StylePackInfo> packInfos(Collection<Long> ids);

    /** 需求订单用的款式：主图、状态、尺码、全部颜色和 SKU（停用的也在）。按请求的顺序；查不到的（被删了）不在 map 里 */
    Map<Long, StyleOrderInfo> orderInfos(Collection<Long> ids);

    /** 几个款式 SKU 各是哪个款的：SKU id → 款式 id；查不到的不在 map 里（订单保存时按 SKU 找款） */
    Map<Long, Long> styleIdsOfSkus(Collection<Long> skuIds);

    /** 款号 → 款式 id（导入订单时按款号找款，不分大小写）：key 是大写的款号；查不到的不在 map 里 */
    Map<String, Long> idsByCodes(Collection<String> codes);

    /** 资料包生效时回写款式上的资料包状态（列表能按它筛，选款式时看它）。不存在 404；不写操作记录（资料包那边记了） */
    void updateTechPackStatus(long styleId, TechPackStatus status);
}
