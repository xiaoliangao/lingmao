package com.lingmao.scm.module.supplier.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.module.supplier.dto.SupplierQuery;
import com.lingmao.scm.module.supplier.dto.SupplierRowVO;
import com.lingmao.scm.module.supplier.entity.CertSummary;
import com.lingmao.scm.module.supplier.entity.ChangeStatus;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.entity.SupplierCategoryRow;
import com.lingmao.scm.module.supplier.entity.SupplierChange;
import com.lingmao.scm.module.supplier.entity.SupplierGoodAtRow;
import com.lingmao.scm.module.supplier.mapper.SupplierChangeMapper;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import com.lingmao.scm.security.LineScope;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 列表和导出共用的：查询条件怎么拼、一页的行怎么补齐分类、待批改动、证照、采购员名字（RFC 004 §8）
 */
@Component
@RequiredArgsConstructor
class SupplierLists {

    /** "证照快到期"：30 天内到期的（已经过期的也算） */
    static final int CERT_EXPIRING_DAYS = 30;

    private final SupplierMapper supplierMapper;
    private final SupplierChangeMapper changeMapper;
    private final UserService userService;
    private final SupplierDicts dicts;
    private final Clock clock;

    /** 列表、导出的条件。表不起别名，子查询里用 sup_supplier.id 指外层这一行 */
    QueryWrapper<Supplier> wrapper(SupplierQuery query) {
        QueryWrapper<Supplier> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            String like = likePattern(query.getKeyword());
            List<String> codes = Keywords.codes(query.getKeyword());
            wrapper.and(w -> {
                w.apply("upper(code) LIKE {0}", Keywords.codeContains(query.getKeyword())).or().apply("name ILIKE {0}", like)
                        .or().apply("short_name ILIKE {0}", like);
                // 按联系人搜只给看得到联系人的：不然拿名字一搜就知道是哪家的联系人（助手令牌不带这个权限，agy 复查 T-a43c80 #1）
                if (com.lingmao.scm.security.FieldPermissions.has(com.lingmao.scm.security.FieldPermissions.CONTACT)) {
                    w.or().apply("contact ILIKE {0}", like);
                }
                if (codes != null) {
                    // 几个编码一起精确查（Keywords）
                    w.or().apply("upper(code) IN (" + IntStream.range(0, codes.size()).mapToObj(i -> "{" + i + "}")
                            .collect(Collectors.joining(",")) + ")", codes.toArray());
                }
            });
        }
        if (StringUtils.hasText(query.getCategory())) {
            inCategory(wrapper, List.of(query.getCategory()));
        }
        // 品类线（RFC 013 §2）：和我的线有交集的；几条线的人还能再按一条筛
        LineScope.current().applyAny(wrapper, "product_lines");
        if (StringUtils.hasText(query.getProductLine())) {
            wrapper.apply("{0} = ANY(product_lines)", query.getProductLine());
        }
        if (query.getStatus() != null && !query.getStatus().isEmpty()) {
            wrapper.in("status", query.getStatus().stream().map(Enum::name).toList());
        }
        wrapper.eq(StringUtils.hasText(query.getLevel()), "level", query.getLevel());
        wrapper.eq(query.getBuyerId() != null, "buyer_id", query.getBuyerId());
        if (Boolean.TRUE.equals(query.getMissingCredit())) {
            wrapper.isNull("credit_code");
        }
        if (Boolean.TRUE.equals(query.getCertExpiring())) {
            wrapper.apply("EXISTS (SELECT 1 FROM sup_supplier_cert t WHERE t.supplier_id = sup_supplier.id AND t.valid_until <= {0})",
                    LocalDate.now(clock).plusDays(CERT_EXPIRING_DAYS));
        }
        if (Boolean.TRUE.equals(query.getToApprove())) {
            // 待我审批：准入审批中、或者有待批改动，申请人不是我（自己不能批自己的）
            long me = Objects.requireNonNullElse(LoginUser.currentUserId(), 0L);
            wrapper.and(w -> w.apply("(status = 'ADMITTING' AND admit_apply_by IS DISTINCT FROM {0})", me)
                    .or().apply("EXISTS (SELECT 1 FROM sup_supplier_change h WHERE h.supplier_id = sup_supplier.id"
                            + " AND h.status = 'PENDING' AND h.apply_by <> {0})", me));
        }
        if (query.getIds() != null && !query.getIds().isEmpty()) {
            wrapper.in("id", query.getIds());
        }
        return wrapper.orderByAsc("code");
    }

    /** 分类里有 categories 其中任一个的（不能是空的） */
    static void inCategory(QueryWrapper<Supplier> wrapper, List<String> categories) {
        String marks = IntStream.range(0, categories.size()).mapToObj(i -> "{" + i + "}").collect(Collectors.joining(", "));
        wrapper.apply("EXISTS (SELECT 1 FROM sup_supplier_category c WHERE c.supplier_id = sup_supplier.id AND c.category IN ("
                + marks + "))", categories.toArray());
    }

    /** 关键字 → ILIKE 的样子：两头加 %，关键字里的 % _ \ 当普通字符（搜"100%"不会匹配所有 100 开头的） */
    static String likePattern(String keyword) {
        return Keywords.contains(keyword, 100);
    }

    /** 一页的行：分类、待批改动、证照、采购员名字各查一次，不每行查 */
    List<SupplierRowVO> rows(List<Supplier> suppliers) {
        if (suppliers.isEmpty()) {
            return List.of();
        }
        List<Long> ids = suppliers.stream().map(Supplier::getId).toList();
        Map<Long, List<String>> categories = categoriesOf(ids);
        Set<Long> pending = changeMapper.selectList(new LambdaQueryWrapper<SupplierChange>()
                        .select(SupplierChange::getSupplierId)
                        .in(SupplierChange::getSupplierId, ids)
                        .eq(SupplierChange::getStatus, ChangeStatus.PENDING))
                .stream().map(SupplierChange::getSupplierId).collect(Collectors.toSet());
        Map<Long, CertSummary> certs = supplierMapper.selectCertSummaries(ids).stream()
                .collect(Collectors.toMap(CertSummary::getSupplierId, Function.identity()));
        Map<Long, String> buyers = buyerNames(suppliers);

        List<SupplierRowVO> rows = new ArrayList<>();
        for (Supplier s : suppliers) {
            SupplierRowVO row = new SupplierRowVO();
            BeanUtils.copyProperties(s, row);
            row.setCategories(categories.getOrDefault(s.getId(), List.of()));
            row.setBuyerName(buyers.get(s.getBuyerId()));
            row.setChangePending(pending.contains(s.getId()));
            CertSummary cert = certs.get(s.getId());
            row.setCertCount(cert == null ? 0 : cert.getCertCount());
            row.setCertEarliestUntil(cert == null ? null : cert.getEarliestUntil());
            rows.add(row);
        }
        return rows;
    }

    Map<Long, String> buyerNames(List<Supplier> suppliers) {
        return new HashMap<>(userService.nicknames(suppliers.stream().map(Supplier::getBuyerId)
                .filter(Objects::nonNull).collect(Collectors.toSet())));
    }

    /** 一批供应商的分类，按字典顺序 */
    Map<Long, List<String>> categoriesOf(Collection<Long> ids) {
        Map<Long, List<String>> raw = supplierMapper.selectCategoriesOf(ids).stream()
                .collect(Collectors.groupingBy(SupplierCategoryRow::getSupplierId,
                        Collectors.mapping(SupplierCategoryRow::getCategory, Collectors.toList())));
        Map<Long, List<String>> sorted = new LinkedHashMap<>();
        raw.forEach((id, values) -> sorted.put(id, dicts.sorted(SupplierDicts.SUPPLIER_CATEGORY, values)));
        return sorted;
    }

    /** 一批供应商的擅长品类名，按品类树的顺序 */
    Map<Long, List<String>> goodAtNamesOf(Collection<Long> ids) {
        return supplierMapper.selectGoodAtOf(ids).stream()
                .collect(Collectors.groupingBy(SupplierGoodAtRow::getSupplierId, LinkedHashMap::new,
                        Collectors.mapping(SupplierGoodAtRow::getCategoryName, Collectors.toList())));
    }
}
