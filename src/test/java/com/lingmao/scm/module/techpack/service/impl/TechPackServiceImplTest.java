package com.lingmao.scm.module.techpack.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.module.techpack.service.CraftStandardService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TechPackServiceImplTest {

    @Mock
    private TechPackMapper techPackMapper;
    @Mock
    private PackOwners packOwners;
    @Mock
    private PackContent packContent;
    @Mock
    private CraftStandardService craftStandardService;

    @InjectMocks
    private TechPackServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), TechPack.class);
    }

    /** 规则（docs/api/01 §3.4）：款式停用了不能建资料包（1000，说是停用了），什么都不插 */
    @Test
    void create_disabledStyle_rejected() {
        when(packOwners.style(100L)).thenReturn(PackFixtures.owner("款式 FS001 已经停用了，资料包不能再建、改、生效"));

        assertThatThrownBy(() -> service.create(100L, PackStage.PROD))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getMessage()).contains("已经停用了");
                });
        verify(techPackMapper, never()).insert(any(TechPack.class));
    }

    /** 规则：同阶段已经有草稿 → 1000，提示改那一版 */
    @Test
    void create_draftExists_rejected() {
        when(packOwners.style(100L)).thenReturn(PackFixtures.owner(null));
        TechPack draft = new TechPack();
        draft.setVersion(3);
        draft.setStatus(PackStatus.DRAFT);
        when(techPackMapper.selectOne(any())).thenReturn(draft);

        assertThatThrownBy(() -> service.create(100L, PackStage.PROD))
                .isInstanceOf(BizException.class).hasMessage("生产 BOM 已经有草稿 V3，改那一版就行");
        verify(techPackMapper, never()).insert(any(TechPack.class));
    }

    /** 规则：已作废的版本不能删、不能修订（1002，按钮是按旧状态显示的） */
    @Test
    void voided_cannotRemoveOrRevise() {
        TechPack voided = new TechPack();
        voided.setId(9L);
        voided.setStatus(PackStatus.VOIDED);
        voided.setStage(PackStage.PROD);
        when(techPackMapper.lockById(9L)).thenReturn(voided);

        for (Runnable action : List.<Runnable>of(() -> service.delete(9L), () -> service.revise(9L))) {
            assertThatThrownBy(action::run)
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED));
        }
        verify(packContent, never()).clear(any(Long.class), any(Boolean.class));
    }
}
