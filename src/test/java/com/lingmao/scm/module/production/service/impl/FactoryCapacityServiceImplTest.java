package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.service.CategoryService;
import com.lingmao.scm.module.production.entity.FactoryCapacity;
import com.lingmao.scm.module.production.mapper.FactoryCapacityMapper;
import com.lingmao.scm.module.production.service.FactoryCapacityService.Demand;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 工厂产能排单时怎么查（PRD 08.02，甲方 11-27 答 Q60） */
@ExtendWith(MockitoExtension.class)
class FactoryCapacityServiceImplTest {

    @Mock
    private FactoryCapacityMapper capacityMapper;
    @Mock
    private SupplierService supplierService;
    @Mock
    private CategoryService categoryService;
    @Mock
    private UserService userService;

    private FactoryCapacityServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-28T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new FactoryCapacityServiceImpl(capacityMapper, supplierService, categoryService, userService, clock);
    }

    /** 这家厂没登记产能：不查，连已排的都不读 */
    @Test
    void noCapacity_noCheck() {
        when(capacityMapper.selectByFactories(anyCollection())).thenReturn(List.of());
        assertThat(service.check(List.of(new Demand("", 7, 3, LocalDate.of(2026, 12, 5), 5000)), List.of())).isEmpty();
        verify(capacityMapper, never()).selectLoads(any(), any());
    }

    /**
     * 产能挂在"女装"上，管它下面"外套 / 风衣"的款；挂在别的品类（男装）上的不管这个款。
     * 每天 100，11-28 ~ 12-05 八天能做 800；已排风衣 750 件，这次 100 件 → 超 50 件；同一个请求里后一块把前一块也算上
     */
    @Test
    void parentCategoryCovers_andBlocks() {
        TreeNode women = new TreeNode(1L, 0L, "W", "女装", 1, 1, 1);
        TreeNode coat = new TreeNode(2L, 1L, "WC", "外套", 2, 1, 1);
        TreeNode trench = new TreeNode(3L, 2L, "WCT", "风衣", 3, 1, 1);
        women.getChildren().add(coat);
        coat.getChildren().add(trench);
        TreeNode men = new TreeNode(9L, 0L, "M", "男装", 1, 2, 1);
        when(categoryService.tree()).thenReturn(List.of(women, men));
        when(capacityMapper.selectByFactories(anyCollection())).thenReturn(List.of(cap(1L, 100), cap(9L, 1)));
        when(capacityMapper.selectStyleCategories(anyCollection())).thenReturn(List.of(new FactoryCapacityMapper.StyleCategoryRow(30, 3L)));
        when(capacityMapper.selectLoads(anyCollection(), anyCollection()))
                .thenReturn(List.of(new FactoryCapacityMapper.LoadRow(7, LocalDate.of(2026, 12, 1), 3L, 650)));
        when(supplierService.refs(anyCollection())).thenReturn(Map.of());

        LocalDate due = LocalDate.of(2026, 12, 5);
        assertThat(service.check(List.of(new Demand("第 1 块：", 7, 30, due, 100)), List.of())).isEmpty();
        List<String> problems = service.check(List.of(new Demand("第 1 块：", 7, 30, due, 100),
                new Demand("第 2 块：", 7, 30, due, 100)), List.of());
        assertThat(problems).singleElement().asString()
                .startsWith("第 2 块：")
                .contains("「女装」", "能做 800 件", "还没做完的 750 件", "超了 50 件");
    }

    private static FactoryCapacity cap(Long categoryId, int daily) {
        FactoryCapacity c = new FactoryCapacity();
        c.setFactoryId(7L);
        c.setCategoryId(categoryId);
        c.setStartMonth(LocalDate.of(2026, 11, 1));
        c.setDailyQty(daily);
        return c;
    }
}
