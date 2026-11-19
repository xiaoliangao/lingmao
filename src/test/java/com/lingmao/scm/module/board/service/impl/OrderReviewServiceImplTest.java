package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.board.dto.OrderReview;
import com.lingmao.scm.module.order.dto.OrderReviewInfo;
import com.lingmao.scm.module.order.dto.OrderReviewStyle;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.BoardFactoryRow;
import com.lingmao.scm.module.production.service.ProductionBoardService;
import com.lingmao.scm.module.purchase.dto.OrderShortageVO;
import com.lingmao.scm.module.purchase.service.MrpService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 审核对话框的评审信息（docs/api/02 §3.8）：资料包照订单域的检查，缺料只按有生效版本的款算，加工厂在手取看板那一行 */
@ExtendWith(MockitoExtension.class)
class OrderReviewServiceImplTest {

    @Mock private OrderService orderService;
    @Mock private MrpService mrpService;
    @Mock private MaterialService materialService;
    @Mock private ProductionBoardService productionBoardService;
    @InjectMocks private OrderReviewServiceImpl service;

    /**
     * 规则：没有生效版本的款不进缺料粗算；缺料带上物料的显示字段；两个款同一个默认加工厂合成一行，
     * 没设默认加工厂的款不出、看板上没有的厂不出
     */
    @Test
    void review_packsShortagesAndLoads() {
        LocalDate due = LocalDate.of(2026, 12, 20);
        when(orderService.reviewInfo(1L)).thenReturn(new OrderReviewInfo(1L, due, List.of(
                new OrderReviewStyle(11L, "FS001", "风衣", 101L, 2, null, 31L),
                new OrderReviewStyle(12L, "FS002", "夹克", 102L, 1, null, 31L),
                new OrderReviewStyle(13L, "FS003", "衬衫", null, null, "没有生效的生产 BOM", null),
                new OrderReviewStyle(14L, "FS004", "长裤", 104L, 1, null, 99L))));
        when(mrpService.orderShortages(eq(1L), eq(Map.of(11L, 101L, 12L, 102L, 14L, 104L)), eq(due), eq(5)))
                .thenReturn(List.of(new OrderShortageVO(201L, new BigDecimal("310"), new BigDecimal("180"), new BigDecimal("130"), 7)));
        MaterialSkuBrief brief = new MaterialSkuBrief();
        brief.setSkuCode("ML0001-KQ");
        brief.setMaterialName("精梳斜纹棉布");
        brief.setColorName("卡其");
        brief.setStockUnit("METER");
        when(materialService.skuBriefs(anySet())).thenReturn(Map.of(201L, brief));
        BoardFactoryRow factory = new BoardFactoryRow();
        factory.setFactoryId(31L);
        factory.setFactoryShortName("示例制衣");
        factory.setWipQty(520);
        factory.setWipCount(2);
        when(productionBoardService.factories(any())).thenReturn(List.of(factory));

        OrderReview review = service.review(1L);

        assertThat(review.packs()).extracting(OrderReview.Pack::problem).containsExactly(null, null, "没有生效的生产 BOM", null);
        assertThat(review.shortages()).singleElement().satisfies(s -> {
            assertThat(s.skuCode()).isEqualTo("ML0001-KQ");
            assertThat(s.skuColorName()).isEqualTo("卡其");
            assertThat(s.unit()).isEqualTo("METER");
            assertThat(s.gap()).isEqualByComparingTo("130");
            assertThat(s.leadDays()).isEqualTo(7);
        });
        assertThat(review.loads()).containsExactly(new OrderReview.Load(31L, "示例制衣", List.of("FS001", "FS002"), 520, 2));
    }
}
