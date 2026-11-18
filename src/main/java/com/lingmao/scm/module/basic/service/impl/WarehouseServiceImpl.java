package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.WarehouseOption;
import com.lingmao.scm.module.basic.dto.WarehouseQuery;
import com.lingmao.scm.module.basic.dto.WarehouseSaveReq;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.entity.Warehouse;
import com.lingmao.scm.module.basic.entity.WarehouseType;
import com.lingmao.scm.module.basic.mapper.WarehouseMapper;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WarehouseServiceImpl implements WarehouseService {

    /** 工厂仓编码的前缀，流水在 sys_serial 的 GC 这一行 */
    static final String FACTORY_PREFIX = "GC";
    static final int FACTORY_CODE_WIDTH = 4;
    /** 取号撞上已有编码时最多再试几次，同物料编码（RFC 003 §7.1） */
    static final int MAX_TRIES = 100;

    private final WarehouseMapper warehouseMapper;
    private final SerialNoGenerator serialNoGenerator;
    private final UserService userService;

    @Override
    public PageResult<WarehouseVO> page(WarehouseQuery query) {
        IPage<WarehouseVO> page = warehouseMapper.selectVOPage(query.toPage(), query);
        fillKeeperNames(page.getRecords());
        return PageResult.of(page);
    }

    @Override
    public WarehouseVO getById(long id) {
        WarehouseVO vo = warehouseMapper.selectVOById(id);
        if (vo == null) {
            throw new BizException(404, "仓库不存在: " + id);
        }
        fillKeeperNames(List.of(vo));
        return vo;
    }

    @Override
    public Map<Long, String> names(Collection<Long> ids) {
        List<Long> wanted = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) return Map.of();
        Map<Long, String> names = warehouseMapper.selectList(new LambdaQueryWrapper<Warehouse>()
                        .select(Warehouse::getId, Warehouse::getName).in(Warehouse::getId, wanted)).stream()
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName));
        for (Long id : wanted) {
            if (!names.containsKey(id)) throw new BizException(404, "仓库不存在: " + id);
        }
        return names;
    }

    @Override
    public List<WarehouseOption> options(String type) {
        return warehouseMapper.selectList(new LambdaQueryWrapper<Warehouse>()
                        .eq(Warehouse::getStatus, Statuses.ENABLED)
                        .eq(Texts.trim(type) != null, Warehouse::getType, type)
                        .orderByAsc(Warehouse::getType)
                        .orderByAsc(Warehouse::getCode))
                .stream().map(WarehouseOption::from).toList();
    }

    @Override
    @Transactional
    public Long create(WarehouseSaveReq req) {
        if (WarehouseType.FACTORY.name().equals(req.getType())) {
            throw new BizException("工厂仓不能手建：供应商分类里有成衣厂、加工厂的，系统自动给它建一个工厂仓");
        }
        String code = Texts.upper(req.getCode());
        ensureCodeFree(code);
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setType(req.getType());
        warehouse.setName(Texts.trim(req.getName()));
        fillEditable(warehouse, req);
        warehouseMapper.insert(warehouse);
        return warehouse.getId();
    }

    @Override
    @Transactional
    public void update(long id, WarehouseSaveReq req) {
        Warehouse warehouse = getEntity(id);
        if (!warehouse.getCode().equals(Texts.upper(req.getCode()))) {
            throw new BizException("仓库编码建好就不能改：编码印在出入库单上。录错了而且没用过的，删掉重建");
        }
        if (!warehouse.getType().equals(req.getType())) {
            throw new BizException("仓库类型建好就不能改");
        }
        String name = Texts.trim(req.getName());
        if (isFactory(warehouse)) {
            if (!warehouse.getName().equals(name)) {
                throw new BizException("工厂仓的名称跟着供应商的简称走，在供应商里改简称");
            }
        } else {
            warehouse.setName(name);
        }
        fillEditable(warehouse, req);
        warehouseMapper.update(warehouse, Updates.replaceById(warehouse));
    }

    @Override
    @Transactional
    public void delete(long id) {
        Warehouse warehouse = getEntity(id);
        if (isFactory(warehouse)) {
            throw new BizException("工厂仓跟着供应商走，不能在这里删：删供应商时一起删；不再用可以停用");
        }
        warehouseMapper.deleteById(id);   // 被单据、库存引用了，外键拦住 → 1005
    }

    @Override
    @Transactional
    public void enable(long id) {
        Statuses.set(warehouseMapper, Warehouse::new, id, Statuses.ENABLED, "仓库");
    }

    @Override
    @Transactional
    public void disable(long id) {
        Statuses.set(warehouseMapper, Warehouse::new, id, Statuses.DISABLED, "仓库");
    }

    @Override
    @Transactional
    public void syncFactoryWarehouse(long supplierId, String supplierCode, String supplierShortName, boolean create) {
        String name = Texts.trim(supplierShortName) + "仓";
        Warehouse existing = warehouseMapper.selectOne(new LambdaQueryWrapper<Warehouse>()
                .eq(Warehouse::getFactoryId, supplierId));
        if (existing != null) {
            if (!name.equals(existing.getName())) {
                existing.setName(name);
                warehouseMapper.update(existing, Updates.replaceById(existing));
            }
            return;
        }
        if (!create) {
            return;
        }
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(factoryCode(supplierCode));
        warehouse.setName(name);
        warehouse.setType(WarehouseType.FACTORY.name());
        warehouse.setFactoryId(supplierId);
        warehouseMapper.insert(warehouse);   // 同时给同一家建：uk_bas_warehouse_factory 拦住后一个 → 1000
    }

    @Override
    @Transactional
    public void deleteFactoryWarehouse(long supplierId) {
        // 被单据、库存引用了，外键拦住 → 1005；调用方的事务整个回滚，供应商也没删
        warehouseMapper.delete(new LambdaQueryWrapper<Warehouse>().eq(Warehouse::getFactoryId, supplierId));
    }

    @Override
    public WarehouseVO factoryWarehouse(long factoryId) {
        Warehouse w = warehouseMapper.selectOne(new LambdaQueryWrapper<Warehouse>().eq(Warehouse::getFactoryId, factoryId));
        return w == null ? null : getById(w.getId());
    }

    /** GC + 供应商编码里的数字；没有数字、或者被占了（手建的仓用了这个编码），按流水一直取到没被占的 */
    private String factoryCode(String supplierCode) {
        String digits = supplierCode == null ? "" : supplierCode.replaceAll("\\D", "");
        if (!digits.isEmpty()) {
            String code = FACTORY_PREFIX + digits;
            if (code.length() <= 32 && !codeTaken(code)) {
                return code;
            }
        }
        for (int i = 0; i < MAX_TRIES; i++) {
            String code = serialNoGenerator.next(FACTORY_PREFIX, FACTORY_CODE_WIDTH);
            if (!codeTaken(code)) {
                return code;
            }
        }
        throw new BizException("工厂仓编码连着 " + MAX_TRIES + " 个都被占了，请联系管理员");
    }

    private boolean codeTaken(String code) {
        return warehouseMapper.exists(new LambdaQueryWrapper<Warehouse>().eq(Warehouse::getCode, code));
    }

    private void ensureCodeFree(String code) {
        if (codeTaken(code)) {
            throw new BizException("仓库编码 " + code + " 已经有了");
        }
    }

    private Warehouse getEntity(long id) {
        Warehouse warehouse = warehouseMapper.selectById(id);
        if (warehouse == null) {
            throw new BizException(404, "仓库不存在: " + id);
        }
        return warehouse;
    }

    private static boolean isFactory(Warehouse warehouse) {
        return WarehouseType.FACTORY.name().equals(warehouse.getType());
    }

    private static void fillEditable(Warehouse warehouse, WarehouseSaveReq req) {
        warehouse.setAddress(Texts.trim(req.getAddress()));
        warehouse.setKeeperId(req.getKeeperId());
        warehouse.setPhone(Texts.trim(req.getPhone()));
        warehouse.setRemark(Texts.trim(req.getRemark()));
    }

    private void fillKeeperNames(List<WarehouseVO> rows) {
        List<Long> ids = rows.stream().map(WarehouseVO::getKeeperId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = userService.nicknames(ids);
        rows.forEach(r -> r.setKeeperName(r.getKeeperId() == null ? null : names.get(r.getKeeperId())));
    }
}
