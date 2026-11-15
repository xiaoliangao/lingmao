package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.dto.SizeGroupCreateReq;
import com.lingmao.scm.module.basic.entity.SizeGroup;
import com.lingmao.scm.module.basic.entity.SizeGroupItem;
import com.lingmao.scm.module.basic.mapper.SizeGroupItemMapper;
import com.lingmao.scm.module.basic.mapper.SizeGroupMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SizeGroupServiceImplTest {

    @Mock
    private SizeGroupMapper sizeGroupMapper;

    @Mock
    private SizeGroupItemMapper sizeGroupItemMapper;

    @InjectMocks
    private SizeGroupServiceImpl sizeGroupService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SizeGroup.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SizeGroupItem.class);
    }

    /** 规则（PRD §6.1.3）：不填基码默认取中间那个；尺码转大写、按填的顺序编 seq */
    @Test
    void create_baseDefaultsToMiddle() {
        when(sizeGroupMapper.exists(any())).thenReturn(false);
        doAnswer(inv -> {
            inv.<SizeGroup>getArgument(0).setId(7L);
            return 1;
        }).when(sizeGroupMapper).insert(any(SizeGroup.class));
        SizeGroupCreateReq req = new SizeGroupCreateReq();
        req.setName("女装字母码");
        req.setSizes("s, m，l、xl xxl");

        sizeGroupService.create(req);

        ArgumentCaptor<SizeGroup> group = ArgumentCaptor.captor();
        verify(sizeGroupMapper).insert(group.capture());
        assertThat(group.getValue().getBaseSize()).isEqualTo("L");
        List<SizeGroupItem> items = capturedItems();
        assertThat(items).extracting(SizeGroupItem::getSizeCode).containsExactly("S", "M", "L", "XL", "XXL");
        assertThat(items).extracting(SizeGroupItem::getSeq).containsExactly(1, 2, 3, 4, 5);
        assertThat(items).extracting(SizeGroupItem::getSizeGroupId).containsOnly(7L);
    }

    /** 规则：只能往后追加，seq 接着最大的编 */
    @Test
    void appendSizes_goesToEnd() {
        when(sizeGroupMapper.selectById(7L)).thenReturn(group(7L));
        when(sizeGroupItemMapper.selectList(any())).thenReturn(List.of(item(7L, 1, "S"), item(7L, 2, "M")));

        sizeGroupService.appendSizes(7L, "l xl");

        List<SizeGroupItem> items = capturedItems();
        assertThat(items).extracting(SizeGroupItem::getSizeCode).containsExactly("L", "XL");
        assertThat(items).extracting(SizeGroupItem::getSeq).containsExactly(3, 4);
    }

    /** 规则：追加的尺码组里已经有了，报错，不插入 */
    @Test
    void appendSizes_duplicate_rejected() {
        when(sizeGroupMapper.selectById(7L)).thenReturn(group(7L));
        when(sizeGroupItemMapper.selectList(any())).thenReturn(List.of(item(7L, 1, "S"), item(7L, 2, "M")));

        assertThatThrownBy(() -> sizeGroupService.appendSizes(7L, "m l"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已经有 M 了");
        verify(sizeGroupItemMapper, never()).insert(anyCollection());
    }

    /** 规则：一次填的里面重复了，报错 */
    @Test
    void parse_duplicateInInput_rejected() {
        assertThatThrownBy(() -> SizeGroupServiceImpl.parse("S M s"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尺码 S 填了两次");
    }

    @SuppressWarnings("unchecked")
    private List<SizeGroupItem> capturedItems() {
        ArgumentCaptor<Collection<SizeGroupItem>> rows = ArgumentCaptor.captor();
        verify(sizeGroupItemMapper).insert(rows.capture());
        return List.copyOf(rows.getValue());
    }

    private static SizeGroup group(Long id) {
        SizeGroup g = new SizeGroup();
        g.setId(id);
        g.setName("女装字母码");
        return g;
    }

    private static SizeGroupItem item(Long groupId, int seq, String code) {
        SizeGroupItem i = new SizeGroupItem();
        i.setSizeGroupId(groupId);
        i.setSeq(seq);
        i.setSizeCode(code);
        return i;
    }
}
