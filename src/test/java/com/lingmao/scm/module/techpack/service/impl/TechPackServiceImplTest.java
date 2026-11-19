package com.lingmao.scm.module.techpack.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import com.lingmao.scm.module.techpack.dto.TechPackCostForm;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
    @Mock private TechPackFiles techPackFiles;
    @Mock private PackForms packForms;
    @Mock private PackBomAccess packBomAccess;
    @Mock private AttachmentService attachmentService;
    @Mock private DocLogService docLogService;

    @InjectMocks
    private TechPackServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), TechPack.class);
    }

    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    @Test void save_unauthorizedCostIsIgnoredBeforeContentConversion() {
        saveCost(false, new TechPackCostForm(), false);
    }

    @Test void save_authorizedNullKeepsCost_andExplicitEmptyCanClear() {
        saveCost(true, null, false);
        org.mockito.Mockito.clearInvocations(packContent, techPackFiles);
        saveCost(true, new TechPackCostForm(), true);
    }

    private void saveCost(boolean authorized, TechPackCostForm cost, boolean withCost) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L, "test"), null,
                authorized ? List.of(new SimpleGrantedAuthority("techpack:cost:query")) : List.of()));
        TechPack p = new TechPack(); p.setId(3L); p.setStatus(PackStatus.DRAFT);
        when(techPackMapper.selectById(3L)).thenReturn(p);
        when(packOwners.of(p)).thenReturn(PackFixtures.owner(null));
        when(packOwners.context(PackFixtures.owner(null))).thenReturn(PackFixtures.ctx());
        TechPackSaveReq req = new TechPackSaveReq(); req.setVersionLock(0); req.setCost(cost);
        PackContent.Content content = new PackContent.Content(List.of(), List.of());
        when(packForms.toContent(req, PackFixtures.ctx().sizes())).thenAnswer(inv -> {
            if (!authorized) assertThat(req.getCost()).isNull();
            return content;
        });
        when(techPackMapper.update(any(TechPack.class), any())).thenReturn(1);
        assertThat(service.save(3L, req)).isEqualTo(1);
        verify(packContent).clear(3L, withCost);
        verify(packContent).write(3L, content, withCost);
        verify(techPackFiles).replace(3L, null);
    }

    /** 规则（RFC 012 §5.4）：复制到换了尺码组的新款，新款没有的尺码、颜色的配料格子和分码用量去掉，其余照旧 */
    @Test
    void fit_dropsSizesAndColorsTheNewStyleLacks() {
        var item = PackFixtures.item(1L, com.lingmao.scm.module.techpack.entity.BomSkuMode.BY_COLOR_SIZE, null, "0", null,
                com.lingmao.scm.module.basic.entity.MaterialSource.PURCHASE);
        PackContent.BomRow row = PackFixtures.row(item,
                List.of(PackFixtures.sku(7L, "S", 71L, null), PackFixtures.sku(7L, "M", 72L, null), PackFixtures.sku(8L, "M", 82L, null)),
                List.of(PackFixtures.size("S", "1.1"), PackFixtures.size("M", "1.2")));
        PackContent.Content content = new PackContent.Content(List.of(row), List.of());

        PackContent.BomRow fitted = TechPackServiceImpl.fit(content, List.of("M", "L"), List.of(7L)).bom().getFirst();

        assertThat(fitted.item()).isSameAs(item);
        assertThat(fitted.skus()).extracting(com.lingmao.scm.module.techpack.entity.BomItemSku::getMaterialSkuId).containsExactly(72L);
        assertThat(fitted.sizes()).extracting(com.lingmao.scm.module.techpack.entity.BomItemSize::getSizeCode).containsExactly("M");
    }

    /** 规则（docs/api/01 §3.4）：款式停用了不能建资料包（1000，说是停用了），什么都不插 */
    @Test
    void create_disabledStyle_rejected() {
        when(packOwners.style(100L)).thenReturn(PackFixtures.owner("款式 FS001 已经停用了，资料包不能再建、改、生效"));

        assertThatThrownBy(() -> service.create(100L))
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

        assertThatThrownBy(() -> service.create(100L))
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
