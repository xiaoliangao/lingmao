package com.lingmao.scm.module.board.controller;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusTone;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.board.dto.HomeTodoQuery;
import com.lingmao.scm.module.board.dto.HomeTodos;
import com.lingmao.scm.module.board.dto.TodoCount;
import com.lingmao.scm.module.board.dto.TodoGroup;
import com.lingmao.scm.module.board.dto.TodoItem;
import com.lingmao.scm.module.board.service.HomeService;
import com.lingmao.scm.module.board.service.OrderReviewService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 首页接口（docs/api/06）：筛选参数接得上、单据 id 按字符串给、看不见的订单评审信息是 404 */
@WebMvcTest(BoardController.class)
@AutoConfigureMockMvc(addFilters = false)
class BoardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HomeService homeService;

    @MockitoBean
    private OrderReviewService reviewService;

    /** 规则：mine、ownerId 从查询参数进来；DocRef 的 id、负责人 id 按字符串给（README 的 ID 边界） */
    @Test
    void todos_bindsFilterAndWritesIdsAsStrings() throws Exception {
        DocRef ref = new DocRef(BizType.ORDER, null, null, "需求订单", 12L, "DD2611180001", "PENDING", "待审核", StatusTone.PENDING, 5L, "张三");
        when(homeService.todos(any())).thenReturn(new HomeTodos(
                List.of(new TodoGroup("AUDIT", 1, List.of(new TodoItem(ref, "需求订单 DD2611180001", "1,200 件", null)))),
                List.of(new TodoCount("ORDER_PENDING", 1)), true));

        mockMvc.perform(get("/api/board/home/todos").param("mine", "false").param("ownerId", "5"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groups[0].items[0].ref.id").value("12"))
                .andExpect(jsonPath("$.data.groups[0].items[0].ref.ownerId").value("5"))
                .andExpect(jsonPath("$.data.counts[0].count").value(1))
                .andExpect(jsonPath("$.data.canSeeAll").value(true));

        ArgumentCaptor<HomeTodoQuery> query = ArgumentCaptor.forClass(HomeTodoQuery.class);
        verify(homeService).todos(query.capture());
        assertThat(query.getValue().isMine()).isFalse();
        assertThat(query.getValue().getOwnerId()).isEqualTo(5L);
    }

    /** 规则：Service 说看不见（404）就原样给，不另外包 */
    @Test
    void review_notVisible_returns404() throws Exception {
        when(reviewService.review(99L)).thenThrow(new BizException(404, "单据不存在"));

        mockMvc.perform(get("/api/board/orders/99/review"))
                .andExpect(jsonPath("$.code").value(404));
    }
}
