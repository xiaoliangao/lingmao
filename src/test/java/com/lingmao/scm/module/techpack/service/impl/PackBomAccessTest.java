package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.techpack.dto.BomItemForm;
import com.lingmao.scm.module.techpack.entity.BomItem;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.service.impl.PackContent.BomRow;
import com.lingmao.scm.security.FieldPermissionDenied;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PackBomAccessTest {

    /** 物料 1 是面料（研发填），物料 2、3 是包材（生产填） */
    private static final long FABRIC = 1L, PACKING = 2L, PACKING2 = 3L;

    @Mock
    private DictDataService dictDataService;
    @Mock
    private MaterialService materialService;

    private PackBomAccess access;

    @BeforeEach
    void setUp() {
        access = new PackBomAccess(dictDataService, materialService);
        lenient().when(dictDataService.listByType("material_type")).thenReturn(List.of(
                new DictOption("面料", "FABRIC", 1, "DEV"), new DictOption("包材", "PACKING", 1, "PROD"), new DictOption("辅料", "TRIM", 1)));
        lenient().when(materialService.materialRefs(any())).thenReturn(List.of(PackFixtures.material(FABRIC, "FABRIC", null, "1", null),
                PackFixtures.material(PACKING, "PACKING", null, "1", null), PackFixtures.material(PACKING2, "PACKING", null, "1", null)));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则（RFC 012 §3.2）：只能填研发的人，改面料随便；包材的行原样带回来（价格变了也算原样）能存 */
    @Test
    void devOnly_editsOwnRows_keepsOthersAsIs() {
        login(PackBomAccess.DEV);
        List<BomRow> old = List.of(row(FABRIC, "1.2"), row(PACKING, "1"));
        BomRow fabric = row(FABRIC, "1.5");
        BomRow packing = row(PACKING, "1.00");
        packing.item().setRefPrice(new BigDecimal("9"));

        assertThatCode(() -> access.check(forms(FABRIC * 10, PACKING * 10), List.of(fabric, packing), () -> old)).doesNotThrowAnyException();
        assertThatCode(() -> access.check(forms(PACKING * 10), List.of(row(PACKING, "1")), () -> old)).doesNotThrowAnyException();   // 删自己的行
    }

    /** 规则：只能填研发的人，包材的行不能改用量、不能加、不能删，也不能把面料行换成包材；403 */
    @Test
    void devOnly_cannotTouchProductionRows_403() {
        login(PackBomAccess.DEV);
        List<BomRow> old = List.of(row(FABRIC, "1.2"), row(PACKING, "1"));

        assertDenied(forms(FABRIC * 10, PACKING * 10), List.of(row(FABRIC, "1.2"), row(PACKING, "2")), old);                // 改用量
        assertDenied(forms(FABRIC * 10, PACKING * 10, null), List.of(row(FABRIC, "1.2"), row(PACKING, "1"), row(PACKING2, "1")), old);  // 加
        assertDenied(forms(FABRIC * 10), List.of(row(FABRIC, "1.2")), old);                                                  // 删
        BomRow swapped = row(PACKING2, "1.2");
        assertDenied(forms(FABRIC * 10, PACKING * 10), List.of(swapped, row(PACKING, "1")), old);                           // 面料行换成包材
    }

    /** 规则：反过来，只能填生产的人改不了面料的行；两样都有的不查，也不去读旧的物料清单 */
    @Test
    void prodOnly_cannotTouchDevRows_bothSkipsCheck() {
        login(PackBomAccess.PROD);
        List<BomRow> old = List.of(row(FABRIC, "1.2"), row(PACKING, "1"));
        assertDenied(forms(FABRIC * 10, PACKING * 10), List.of(row(FABRIC, "1.3"), row(PACKING, "1")), old);
        assertThatCode(() -> access.check(forms(FABRIC * 10, PACKING * 10, null), List.of(row(FABRIC, "1.2"), row(PACKING, "3"),
                row(PACKING2, "1")), () -> old)).doesNotThrowAnyException();

        login(PackBomAccess.DEV, PackBomAccess.PROD);
        @SuppressWarnings("unchecked")
        Supplier<List<BomRow>> loadOld = mock(Supplier.class);
        access.check(forms(FABRIC * 10), List.of(row(PACKING, "5")), loadOld);
        verifyNoInteractions(loadOld);
    }

    private void assertDenied(List<BomItemForm> forms, List<BomRow> submitted, List<BomRow> old) {
        assertThatThrownBy(() -> access.check(forms, submitted, () -> old)).isInstanceOf(FieldPermissionDenied.class)
                .extracting("code").isEqualTo(403);
    }

    private static BomRow row(long materialId, String usage) {
        BomItem item = PackFixtures.item(materialId, BomSkuMode.FIXED, usage, "0", null, MaterialSource.PURCHASE);
        return PackFixtures.row(item, List.of(PackFixtures.sku(null, null, materialId * 100, null)), List.of());
    }

    /** 每一行带回来的明细 id（物料 id × 10，和 PackFixtures.item 一样），null = 新加的行 */
    private static List<BomItemForm> forms(Long... ids) {
        return Arrays.stream(ids).map(id -> {
            BomItemForm f = new BomItemForm();
            f.setId(id);
            return f;
        }).toList();
    }

    private static void login(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L, "test"), null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
