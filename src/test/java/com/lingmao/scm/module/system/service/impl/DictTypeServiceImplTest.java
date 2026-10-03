package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.DictTypeSaveReq;
import com.lingmao.scm.module.system.entity.DictType;
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

/**
 * Service 单测样板：只测业务规则，不连数据库、不起 Spring。
 * 为什么能不连库：Mapper 用 @Mock 换成假的，"数据库里有什么"由 when(...).thenReturn(...) 说了算；
 * @InjectMocks 用构造器把假 Mapper 塞进真的 ServiceImpl。所以一个测试几十毫秒，规则错了立刻知道。
 * 每个测试只钉一条规则，名字写成 方法_场景_期望，结构固定三段：准备（Arrange）→ 调用（Act）→ 断言（Assert）。
 */
@ExtendWith(MockitoExtension.class)
class DictTypeServiceImplTest {

    @Mock
    private DictTypeMapper dictTypeMapper;

    @InjectMocks
    private DictTypeServiceImpl dictTypeService;

    /** 规则：编码已存在不能新增，而且不能走到 insert（先查重再写库）。 */
    @Test
    void create_codeExists_throwsAndNotInsert() {
        when(dictTypeMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> dictTypeService.create(req("season")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("编码已存在");
        verify(dictTypeMapper, never()).insert(any(DictType.class));
    }

    /** 规则：新增成功返回数据库生成的 id。insert 回填 id 是数据库的事，这里用 doAnswer 模拟回填。 */
    @Test
    void create_ok_returnsGeneratedId() {
        when(dictTypeMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            DictType entity = inv.getArgument(0);
            entity.setId(42L);
            return 1;
        }).when(dictTypeMapper).insert(any(DictType.class));

        Long id = dictTypeService.create(req("season"));

        assertThat(id).isEqualTo(42L);
    }

    /** 规则：查不到抛业务异常，code 是 404，前端据此提示"不存在"而不是"服务器错误"。 */
    @Test
    void getById_notFound_throws404() {
        when(dictTypeMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> dictTypeService.getById(99L))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(404);
    }

    /** 规则：删除不存在的记录要报 404，不能"静默成功"，也不能发出 delete 语句。 */
    @Test
    void delete_notFound_throwsAndNotDelete() {
        when(dictTypeMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> dictTypeService.delete(99L)).isInstanceOf(BizException.class);
        verify(dictTypeMapper, never()).deleteById(99L);
    }

    private DictTypeSaveReq req(String code) {
        DictTypeSaveReq req = new DictTypeSaveReq();
        req.setCode(code);
        req.setName("季节");
        return req;
    }
}
