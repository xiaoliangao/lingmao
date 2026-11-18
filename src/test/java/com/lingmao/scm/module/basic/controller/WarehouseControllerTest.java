package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.module.basic.dto.WarehouseOption;
import com.lingmao.scm.module.basic.service.WarehouseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(WarehouseController.class)
@AutoConfigureMockMvc(addFilters = false)
class WarehouseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WarehouseService warehouseService;

    /** 规则：类型只认 MATERIAL、PRODUCT、FACTORY，别的在入口返回 400 */
    @Test
    void create_badType_returns400() throws Exception {
        mockMvc.perform(post("/api/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "WL01", "name": "面料仓", "type": "OTHER"}
                                """))
                .andExpect(jsonPath("$.code").value(400));

        verify(warehouseService, never()).create(any());
    }

    /** 规则：/options 按类型筛，type 传给 Service */
    @Test
    void options_byType() throws Exception {
        when(warehouseService.options("FACTORY")).thenReturn(List.of(new WarehouseOption(3L, "GC0012", "华锦仓", "FACTORY", 9L)));

        mockMvc.perform(get("/api/warehouses/options").param("type", "FACTORY"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].factoryId").value("9"));
    }
}
