package com.lingmao.scm.module.basic.service;

import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.basic.dto.MaterialCreateReq;
import com.lingmao.scm.module.basic.dto.MaterialDetailVO;
import com.lingmao.scm.module.basic.dto.MaterialDupVO;
import com.lingmao.scm.module.basic.dto.MaterialListVO;
import com.lingmao.scm.module.basic.dto.MaterialPickQuery;
import com.lingmao.scm.module.basic.dto.MaterialPickVO;
import com.lingmao.scm.module.basic.dto.MaterialPurchaseInfo;
import com.lingmao.scm.module.basic.dto.MaterialQuery;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSaveReq;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuSaveReq;
import com.lingmao.scm.module.basic.dto.MaterialSkuVO;
import com.lingmao.scm.module.basic.dto.MaterialTypeReq;
import com.lingmao.scm.module.basic.dto.SkuBatchReq;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.system.dto.DictOption;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 物料和物料 SKU（RFC 003 §7）。编码 = 分类码 + 4 位（取到的号被导入的老编码占了就再取）；分类、库存单位建好不改；
 * SKU = 颜色 × 规格，SKU 码 = 物料编码-颜色码[-规格码]，建好不改；物料至少留一个 SKU
 */
public interface MaterialService {

    PageResult<MaterialListVO> page(MaterialQuery query);

    /** 物料 + 全部 SKU + 图片 + 领猫附加属性 */
    MaterialDetailVO detail(long id);

    /** 新建物料和第一批 SKU，返回物料 id */
    Long create(MaterialCreateReq req);

    /**
     * 当场加一个物料类型（字典 material_type 的一项，PRD §6.1.5 规则 6）：名称不能和已有的重复，停用的也算；
     * 值自动编 CUSTOM_1、CUSTOM_2……，备料比不填 = 3
     */
    DictOption addType(MaterialTypeReq req);

    /** 只改物料自己的列。分类、库存单位和原来不一样报 1000 */
    void update(long id, MaterialSaveReq req);

    /** 连同 SKU、图片、操作记录一起删；有一个 SKU 被引用了外键拦住 → 1005，整个回滚 */
    void delete(long id);

    void enable(long id);

    void disable(long id);

    /** 批量加 SKU：颜色 × 规格，已有的跳过。返回这次新建的 */
    List<MaterialSkuVO> addSkus(long id, SkuBatchReq req);

    void updateSku(long id, long skuId, MaterialSkuSaveReq req);

    /** 物料至少留一个 SKU；被引用了外键拦住 → 1005 */
    void deleteSku(long id, long skuId);

    void enableSku(long id, long skuId);

    void disableSku(long id, long skuId);

    /** 物料图整组替换，第一张是主图（RFC 003 §2） */
    List<AttachmentVO> replaceImages(long id, List<AttachmentReq> files);

    /** SKU 图整组替换 */
    List<AttachmentVO> replaceSkuImages(long id, long skuId, List<AttachmentReq> files);

    List<DocLogVO> logs(long id);

    /** 建档查重，只提示不拦：同分类同名、同默认供应商同货号，最多 10 条 */
    List<MaterialDupVO> duplicates(Long categoryId, String name, Long defaultSupplierId, String supplierItemNo, Long excludeId);

    /** 选物料 SKU（DataPick）：启用物料的启用 SKU */
    PageResult<MaterialPickVO> pick(MaterialPickQuery query);

    /** 物料：给下游用。有不存在的 id 抛 404 */
    List<MaterialRef> materialRefs(Collection<Long> materialIds);

    /** 物料 SKU：给下游用。有不存在的 id 抛 404 */
    List<MaterialSkuRef> skuRefs(Collection<Long> skuIds);

    /**
     * 一批 SKU 的显示字段（编码、名称、颜色、规格、库存单位、图），skuId → brief。单据明细、库存列表显示用；
     * 不存在的 id 不在 map 里（被单据引用的 SKU 有外键，删不掉）
     */
    Map<Long, MaterialSkuBrief> skuBriefs(Collection<Long> skuIds);

    /** 一批 SKU 买的时候要的东西（算料、采购单、补货提醒），skuId → info；不存在的 id 不在 map 里 */
    Map<Long, MaterialPurchaseInfo> purchaseInfos(Collection<Long> skuIds);

    /** 按 SKU 码找 id（导入盘点表用），领猫编码也认（RFC 012 §4.2），不分大小写；key 是大写的码，找不到的不在 map 里 */
    Map<String, Long> skuIdsByCodes(Collection<String> codes);

    /**
     * 按物料名称找启用的物料和它启用的 SKU（资料包从 BOM 表导入，RFC 011 §4.2）：名称去掉首尾空格后完全相同才算，不分大小写。
     * key 是去掉首尾空格、转小写的名称 → 这个名字的物料（可能几个同名的）各自的 SKU，按物料编码、SKU 编码排；没有启用 SKU 的物料不在里面
     */
    Map<String, List<MaterialSkuRef>> enabledSkusByNames(Collection<String> names);

    /** 几种物料启用的 SKU（BOM 导入时人工挑了物料的行）：物料 id → SKU，按 SKU 编码排；停用的物料、没有启用 SKU 的不在 map 里 */
    Map<Long, List<MaterialSkuRef>> enabledSkusOf(Collection<Long> materialIds);

    byte[] importTemplate();

    ImportResult importExcel(MultipartFile file);

    /** 导出：一个物料一行、一个 SKU 一行，两张表，列和导入模板一样 */
    byte[] export(MaterialQuery query);

    /**
     * 新建物料的编码预览（RFC 018 §4.6）：按适用于这个物料类型的编码规则，只看不取，保存时以真取到的为准。
     * 分类要是最末一级
     */
    String codePreview(Long categoryId, String materialType);
}
