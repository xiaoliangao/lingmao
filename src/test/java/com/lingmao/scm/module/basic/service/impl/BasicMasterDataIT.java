package com.lingmao.scm.module.basic.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.dto.CompanySaveReq;
import com.lingmao.scm.module.basic.dto.SizeGroupCreateReq;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.basic.dto.WarehouseQuery;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.basic.service.SizeGroupService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基础资料里 Mockito 测不出的（RFC 003 §16）：工厂仓同步的唯一索引和改名、主体公司"至少留一个启用的"的行锁、
 * 尺码组的批量插入。供应商的行用 JDBC 直接插（supplier 域第 2b 步做，这里只要一行能被外键指向的）
 */
@IntegrationTest
class BasicMasterDataIT {

    @Autowired
    private WarehouseService warehouseService;
    @Autowired
    private CompanyService companyService;
    @Autowired
    private SizeGroupService sizeGroupService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 规则（RFC 003 §6）：没有就建（GC + 供应商编码里的数字），再调只改名、不会有第二个；
     * 编码被手建的仓占了另取流水；工厂仓被单据引用以外都能跟着供应商删
     */
    @Test
    void syncFactoryWarehouse_createRenameDelete() {
        long n = ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
        long supplierId = supplier("GYS" + n, "华锦" + n);

        warehouseService.syncFactoryWarehouse(supplierId, "GYS" + n, "华锦" + n, true);
        warehouseService.syncFactoryWarehouse(supplierId, "GYS" + n, "新华锦" + n, true);

        List<WarehouseVO> rows = factoryWarehouses(supplierId);
        assertThat(rows).singleElement().satisfies(w -> {
            assertThat(w.getCode()).isEqualTo("GC" + n);
            assertThat(w.getName()).isEqualTo("新华锦" + n + "仓");
            assertThat(w.getType()).isEqualTo("FACTORY");
        });
        WarehouseQuery query = new WarehouseQuery();
        query.setKeyword("GC" + n);
        assertThat(warehouseService.page(query).getList()).singleElement()
                .extracting(WarehouseVO::getFactoryName).isEqualTo("华锦" + n);   // 列表连供应商取简称（这时供应商没改名）

        long other = supplier("X" + n, "撞号" + n);   // 编码里的数字和上一家一样：GC + n 已经被占了，按流水取
        warehouseService.syncFactoryWarehouse(other, "X" + n, "撞号" + n, true);
        assertThat(factoryWarehouses(other)).singleElement().extracting(WarehouseVO::getCode).asString().matches("GC\\d{4,}");

        warehouseService.deleteFactoryWarehouse(supplierId);
        assertThat(factoryWarehouses(supplierId)).isEmpty();
    }

    /** 规则：同一家同时同步两次，唯一索引 uk_bas_warehouse_factory 拦住后一个，最后只有一个工厂仓 */
    @Test
    void syncFactoryWarehouse_concurrent_onlyOne() throws Exception {
        long n = ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
        long supplierId = supplier("GYS" + n, "并发" + n);
        int threads = 4;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int failed = 0;
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    warehouseService.syncFactoryWarehouse(supplierId, "GYS" + n, "并发" + n, true);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(DuplicateKeyException.class);
                    failed++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(factoryWarehouses(supplierId)).hasSize(1);
        assertThat(failed).isLessThan(threads);
    }

    /** 规则：工厂仓必须绑工厂、别的仓不能绑（ck_bas_warehouse_factory），绕过 Service 直接写也写不进 */
    @Test
    void warehouseFactoryCheckConstraint() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO bas_warehouse (code, name, type) VALUES (?, '没绑工厂', 'FACTORY')", "CK" + System.nanoTime()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 规则（RFC 003 §3.1）：两个人同时停用最后两个启用的主体公司，只能成功一个，至少留一个启用的。
     * 先把库里别的测试留下的都停掉，只剩这次建的两个
     */
    @Test
    void disableCompanies_concurrent_oneStaysEnabled() throws Exception {
        jdbcTemplate.update("UPDATE bas_company SET status = 0");
        long a = companyService.create(company());
        long b = companyService.create(company());
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int rejected = 0;
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        companyService.disable(a);
                        return null;
                    }),
                    pool.submit(() -> {
                        start.await();
                        companyService.disable(b);
                        return null;
                    }));
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(BizException.class).hasMessageContaining("最后一个启用的主体公司");
                    rejected++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(rejected).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bas_company WHERE status = 1", Integer.class)).isEqualTo(1);
    }

    /** 规则：尺码一次批量插进去，顺序、基码对；追加接在后面；删尺码组连尺码一起删 */
    @Test
    void sizeGroup_batchInsertAppendDelete() {
        SizeGroupCreateReq req = new SizeGroupCreateReq();
        req.setName("IT尺码组" + System.nanoTime());
        req.setSizes("155/80A 160/84A 165/88A 170/92A");
        long id = sizeGroupService.create(req);
        sizeGroupService.appendSizes(id, "175/96A");

        SizeGroupVO group = sizeGroupService.getById(id);
        assertThat(group.sizes()).containsExactly("155/80A", "160/84A", "165/88A", "170/92A", "175/96A");
        assertThat(group.baseSize()).isEqualTo("160/84A");

        sizeGroupService.delete(id);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bas_size_group_item WHERE size_group_id = ?",
                Integer.class, id)).isZero();
    }

    private List<WarehouseVO> factoryWarehouses(long supplierId) {
        return jdbcTemplate.queryForList("SELECT id FROM bas_warehouse WHERE factory_id = ?", Long.class, supplierId)
                .stream().map(warehouseService::getById).toList();
    }

    private long supplier(String code, String shortName) {
        return jdbcTemplate.queryForObject("INSERT INTO sup_supplier (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, code, shortName + "有限公司", shortName);
    }

    private static CompanySaveReq company() {
        String code = Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36 * 36, 36L * 36 * 36 * 36 * 36 * 36), 36)
                .toUpperCase();
        CompanySaveReq req = new CompanySaveReq();
        req.setCode(code);
        req.setName("IT主体" + code);
        req.setShortName("IT" + code);
        return req;
    }
}
