package com.lingmao.scm.module.supplier.service.impl;

import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.entity.SupplierCategoryRow;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.dto.FactoryBrief;
import com.lingmao.scm.module.system.service.FactoryDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 工厂账号（RFC 019 §7）用的供应商信息：是不是加工厂、成衣厂（SupplierService.FACTORY_CATEGORIES），合不合作 */
@Service
@RequiredArgsConstructor
public class SupplierFactoryDirectory implements FactoryDirectory {

    private final SupplierMapper supplierMapper;

    @Override
    public Map<Long, FactoryBrief> find(Collection<Long> supplierIds) {
        List<Long> ids = supplierIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        Map<Long, List<String>> categories = supplierMapper.selectCategoriesOf(ids).stream()
                .collect(Collectors.groupingBy(SupplierCategoryRow::getSupplierId,
                        Collectors.mapping(SupplierCategoryRow::getCategory, Collectors.toList())));
        Map<Long, FactoryBrief> out = new HashMap<>();
        for (Supplier s : supplierMapper.selectByIds(ids)) {
            out.put(s.getId(), new FactoryBrief(s.getId(), s.getCode(), s.getShortName(),
                    SupplierService.isFactory(categories.getOrDefault(s.getId(), List.of())), s.getStatus() == SupplierStatus.ACTIVE));
        }
        return out;
    }
}
