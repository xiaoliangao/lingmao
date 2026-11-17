package com.lingmao.scm.module.basic.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.basic.dto.WarehouseOption;
import com.lingmao.scm.module.basic.dto.WarehouseQuery;
import com.lingmao.scm.module.basic.dto.WarehouseSaveReq;
import com.lingmao.scm.module.basic.dto.WarehouseVO;

import java.util.List;

/**
 * 仓库（RFC 003 §6）：物料仓、成品仓在页面上手建；工厂仓由 supplier 在它自己的事务里调下面两个方法同步，
 * 页面上不能建、不能删，只能改地址、负责人、电话、备注和启用停用
 */
public interface WarehouseService {

    PageResult<WarehouseVO> page(WarehouseQuery query);

    WarehouseVO getById(long id);

    /** 下拉：启用的，type 为空不筛 */
    List<WarehouseOption> options(String type);

    /** 只能建物料仓、成品仓；工厂仓报 1000 */
    Long create(WarehouseSaveReq req);

    /** 编码、类型建好不改；工厂仓的名称也不能在这里改 */
    void update(long id, WarehouseSaveReq req);

    /** 工厂仓不能在这里删（删供应商时一起删）；被单据、库存引用了外键拦住 → 1005 */
    void delete(long id);

    void enable(long id);

    void disable(long id);

    /**
     * 工厂仓跟着供应商走（RFC 003 §6、RFC 004 §6）：这家有工厂仓就把名称改成"简称 + 仓"；没有时 create 为 true 才建，
     * 编码 GC + 供应商编码里的数字（GYS0004 → GC0004），没有数字或者被占了，用 next("GC", 4) 一直取到没被占的。
     * 要在调用方的事务里调。两个请求同时给同一家建仓，uk_bas_warehouse_factory 拦住后一个（1000 刷新再试）
     */
    void syncFactoryWarehouse(long supplierId, String supplierCode, String supplierShortName, boolean create);

    /** 删供应商之前调：工厂仓没被引用就删；被引用了外键拦住，整个删除回滚，报 1005。这家没有工厂仓什么也不做 */
    void deleteFactoryWarehouse(long supplierId);

    /** 这家加工厂的工厂仓（一家一个，停用的也返回）；没有是 null：领料的入仓、退料的出仓用 */
    WarehouseVO factoryWarehouse(long factoryId);
}
