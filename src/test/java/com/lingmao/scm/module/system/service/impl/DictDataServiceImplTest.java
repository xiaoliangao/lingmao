package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.entity.DictData;
import com.lingmao.scm.module.system.mapper.DictDataMapper;
import com.lingmao.scm.module.system.mapper.DictTypeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private DictDataServiceImpl dictDataService;

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
        verify(dictDataMapper, never()).updateById(any(DictData.class));
    }

    private DictDataSaveReq req(String typeCode, String value) {
        DictDataSaveReq req = new DictDataSaveReq();
        req.setTypeCode(typeCode);
        req.setLabel("春季");
        req.setValue(value);
        return req;
    }
}