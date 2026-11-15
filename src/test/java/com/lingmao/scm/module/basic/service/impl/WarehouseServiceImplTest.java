package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.WarehouseSaveReq;
import com.lingmao.scm.module.basic.entity.Warehouse;
import com.lingmao.scm.module.basic.mapper.WarehouseMapper;
import com.lingmao.scm.module.system.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 页面建工厂仓被拒；同步：没有就建、有就改名、关着 create 不建；GC 编码被占时另取（RFC 003 §16） */
@ExtendWith(MockitoExtension.class)
class WarehouseServiceImplTest {

    @Mock
    private WarehouseMapper warehouseMapper;

    @Mock
    private SerialNoGenerator serialNoGenerator;

    @Mock
    private UserService userService;

    @InjectMocks
    private WarehouseServiceImpl warehouseService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Warehouse.class);
    }

    /** 规则：工厂仓不能在页面上手建 */
    @Test
    void create_factory_rejected() {
        WarehouseSaveReq req = new WarehouseSaveReq();
        req.setCode("GC01");
        req.setName("某工厂仓");
        req.setType("FACTORY");

        assertThatThrownBy(() -> warehouseService.create(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("工厂仓不能手建");
        verify(warehouseMapper, never()).insert(any(Warehouse.class));
    }

    /** 规则：没有就建，编码 GC + 供应商编码里的数字，名称 = 简称 + 仓 */
    @Test
    void sync_missing_createsWithSupplierDigits() {
        when(warehouseMapper.selectOne(any())).thenReturn(null);
        when(warehouseMapper.exists(any())).thenReturn(false);

        warehouseService.syncFactoryWarehouse(9L, "GYS0012", "华锦", true);

        ArgumentCaptor<Warehouse> saved = ArgumentCaptor.captor();
        verify(warehouseMapper).insert(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("GC0012");
        assertThat(saved.getValue().getName()).isEqualTo("华锦仓");
        assertThat(saved.getValue().getType()).isEqualTo("FACTORY");
        assertThat(saved.getValue().getFactoryId()).isEqualTo(9L);
        verify(serialNoGenerator, never()).next(any(), any(Integer.class));
    }

    /** 规则：GC + 数字被手建的仓占了，按流水取，跳过也被占的 */
    @Test
    void sync_codeTaken_takesNextSerialThatIsFree() {
        when(warehouseMapper.selectOne(any())).thenReturn(null);
        when(warehouseMapper.exists(any())).thenReturn(true, true, false);   // GC0012 占了，GC0001 也占了，GC0002 空
        when(serialNoGenerator.next("GC", 4)).thenReturn("GC0001", "GC0002");

        warehouseService.syncFactoryWarehouse(9L, "GYS0012", "华锦", true);

        ArgumentCaptor<Warehouse> saved = ArgumentCaptor.captor();
        verify(warehouseMapper).insert(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("GC0002");
    }

    /** 规则：已经有了就只改名，不再建第二个；名称没变连 update 都不发；create = false 时没有也不建 */
    @Test
    void sync_existing_renamesOnly_andCreateFalseSkips() {
        Warehouse existing = new Warehouse();
        existing.setId(3L);
        existing.setName("华锦仓");
        existing.setType("FACTORY");
        existing.setFactoryId(9L);
        when(warehouseMapper.selectOne(any())).thenReturn(existing);

        warehouseService.syncFactoryWarehouse(9L, "GYS0012", "华锦", true);    // 第二次调，名称没变
        verify(warehouseMapper, never()).update(any(), any());

        warehouseService.syncFactoryWarehouse(9L, "GYS0012", "新华锦", true);
        assertThat(existing.getName()).isEqualTo("新华锦仓");
        verify(warehouseMapper).update(any(), any());
        verify(warehouseMapper, never()).insert(any(Warehouse.class));

        when(warehouseMapper.selectOne(any())).thenReturn(null);
        warehouseService.syncFactoryWarehouse(10L, "GYS0013", "某某", false);
        verify(warehouseMapper, never()).insert(any(Warehouse.class));
    }

    /** 规则：工厂仓不能在仓库页改名、不能删 */
    @Test
    void factoryWarehouse_renameAndDeleteFromPage_rejected() {
        Warehouse factory = new Warehouse();
        factory.setId(3L);
        factory.setCode("GC0012");
        factory.setName("华锦仓");
        factory.setType("FACTORY");
        when(warehouseMapper.selectById(3L)).thenReturn(factory);
        WarehouseSaveReq req = new WarehouseSaveReq();
        req.setCode("GC0012");
        req.setName("改个名");
        req.setType("FACTORY");

        assertThatThrownBy(() -> warehouseService.update(3L, req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("在供应商里改简称");
        assertThatThrownBy(() -> warehouseService.delete(3L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能在这里删");
        verify(warehouseMapper, never()).deleteById(any(Long.class));
    }
}
