package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.system.cache.DictCache;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.entity.DictData;
import com.lingmao.scm.module.system.mapper.DictDataMapper;
import com.lingmao.scm.module.system.mapper.DictTypeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DictDataServiceImplTest {

    @Mock
    private DictDataMapper dictDataMapper;

    @Mock
    private DictTypeMapper dictTypeMapper;

    @Mock
    private DictCache dictCache;

    /** 代工开关用真的，按默认关着（RFC 003 §3.3） */
    @Spy
    private TradeFeature tradeFeature = new TradeFeature(false);

    @InjectMocks
    private DictDataServiceImpl dictDataService;

    /** 修改用 Updates.replaceById，要先建表信息（见 RoleServiceImplTest） */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DictData.class);
    }

    /** 规则：字典类型不存在，不能往它下面加数据 */
    @Test
    void create_typeNotExists_throwsAndNotInsert() {
        when(dictTypeMapper.exists(any())).thenReturn(false);

        assertThatThrownBy(() -> dictDataService.create(req("season", "1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("字典类型不存在");
        verify(dictDataMapper, never()).insert(any(DictData.class));
    }

    /** 规则：同一类型下 value 不能重复 */
    @Test
    void create_valueExists_throwsAndNotInsert() {
        when(dictTypeMapper.exists(any())).thenReturn(true);
        when(dictDataMapper.exists(any())).thenReturn(true);

        assertThatThrownBy(() -> dictDataService.create(req("season", "1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("存储值已存在");
        verify(dictDataMapper, never()).insert(any(DictData.class));
    }

    /** 规则：两项检查都通过才插入，返回数据库生成的 id */
    @Test
    void create_ok_returnsGeneratedId() {
        when(dictTypeMapper.exists(any())).thenReturn(true);
        when(dictDataMapper.exists(any())).thenReturn(false);
        doAnswer(inv -> {
            DictData entity = inv.getArgument(0);
            entity.setId(7L);
            return 1;
        }).when(dictDataMapper).insert(any(DictData.class));

        Long id = dictDataService.create(req("season", "1"));

        assertThat(id).isEqualTo(7L);
    }

    /** 规则：修改不存在的记录报 404，不发 update */
    @Test
    void update_notFound_throws404AndNotUpdate() {
        when(dictDataMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> dictDataService.update(99L, req("season", "1")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(404);
        verify(dictDataMapper, never()).update(any(DictData.class), any());
    }

    private DictDataSaveReq req(String typeCode, String value) {
        DictDataSaveReq req = new DictDataSaveReq();
        req.setTypeCode(typeCode);
        req.setLabel("春季");
        req.setValue(value);
        return req;
    }

    /** 规则：缓存里有，直接返回，不查库 */
    @Test
    void listByType_cacheHit_noDbQuery() {
        List<DictOption> cached = List.of(new DictOption("春季", "1", 1));
        when(dictCache.get("season")).thenReturn(cached);

        assertThat(dictDataService.listByType("season")).isEqualTo(cached);
        verify(dictDataMapper, never()).selectList(any());
    }

    /**
     * 规则：缓存里没有，查库，取 label、value、status，再放进缓存。
     * 停用的也要（旧数据里存着停用的值，显示时要翻成文字），查询条件里不能有 status
     */
    @Test
    void listByType_cacheMiss_includesDisabledWithStatus() {
        DictData spring = new DictData();
        spring.setLabel("春季");
        spring.setValue("1");
        spring.setStatus(1);
        DictData old = new DictData();
        old.setLabel("旧款季");
        old.setValue("9");
        old.setStatus(0);
        when(dictCache.get("season")).thenReturn(null);
        when(dictDataMapper.selectList(any())).thenReturn(List.of(spring, old));

        List<DictOption> options = dictDataService.listByType("season");

        assertThat(options).containsExactly(new DictOption("春季", "1", 1), new DictOption("旧款季", "9", 0));
        assertThat(options).extracting(DictOption::enabled).containsExactly(true, false);
        verify(dictCache).put("season", options);
    }

    /** 规则：字典项不能挪到别的类型（挪了等于改了它的身份，和改 value 一样，RFC 003 §4.2） */
    @Test
    void update_typeChanged_rejected() {
        when(dictDataMapper.selectById(5L)).thenReturn(item(5L, "season", "SPRING", false));
        DictDataSaveReq req = req("color_family", "SPRING");

        assertThatThrownBy(() -> dictDataService.update(5L, req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能挪到别的类型");
        verify(dictDataMapper, never()).update(any(DictData.class), any());
    }

    /** 规则：value 建好以后不能改，所有项都是，不只内置项（RFC 003 §4.2） */
    @Test
    void update_valueChanged_rejected() {
        when(dictDataMapper.selectById(5L)).thenReturn(item(5L, "season", "SPRING", false));

        assertThatThrownBy(() -> dictDataService.update(5L, req("season", "SPRING2")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("存储值建好就不能改");
        verify(dictDataMapper, never()).update(any(DictData.class), any());
    }

    /** 规则：内置项的名称、排序、数值能改，改完删这个类型的缓存 */
    @Test
    void update_builtinLabel_savedAndEvicts() {
        when(dictDataMapper.selectById(5L)).thenReturn(item(5L, "unit", "PIECE", true));
        DictDataSaveReq req = req("unit", "PIECE");
        req.setLabel("件（成衣）");

        dictDataService.update(5L, req);

        verify(dictDataMapper).update(any(DictData.class), any());
        verify(dictCache).evictAfterCommit("unit");
    }

    /** 规则：内置项不能停用 */
    @Test
    void update_builtinDisabled_rejected() {
        when(dictDataMapper.selectById(5L)).thenReturn(item(5L, "unit", "PIECE", true));
        DictDataSaveReq req = req("unit", "PIECE");
        req.setStatus(0);

        assertThatThrownBy(() -> dictDataService.update(5L, req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内置项，不能停用");
        verify(dictDataMapper, never()).update(any(DictData.class), any());
    }

    /** 规则：内置项不能删；普通项照旧逻辑删除 */
    @Test
    void delete_builtin_rejected() {
        when(dictDataMapper.selectById(5L)).thenReturn(item(5L, "style_source", "OWN", true));

        assertThatThrownBy(() -> dictDataService.delete(5L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内置项，不能删除");
        verify(dictDataMapper, never()).deleteById(any(Long.class));
    }

    /** 规则：TRADE 关时，下拉选项里没有"客户来样"（缓存里是全的，取出来再滤） */
    @Test
    void listByType_tradeOff_hidesCustomerSample() {
        when(dictCache.get("style_source")).thenReturn(List.of(
                new DictOption("自主开发", "OWN", 1), new DictOption("客户来样", "CUSTOMER_SAMPLE", 1)));

        assertThat(dictDataService.listByType("style_source")).extracting(DictOption::value).containsExactly("OWN");
    }

    private static DictData item(Long id, String typeCode, String value, boolean builtin) {
        DictData d = new DictData();
        d.setId(id);
        d.setTypeCode(typeCode);
        d.setLabel("名称");
        d.setValue(value);
        d.setStatus(1);
        d.setBuiltin(builtin);
        return d;
    }
}
