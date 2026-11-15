package com.lingmao.scm.common.mybatis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 外键冲突时把表名翻成中文用（GlobalExceptionHandler）：查不到一律 null，不能让报错的处理本身再报错 */
@ExtendWith(MockitoExtension.class)
class TableCommentsTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private TableComments tableComments;

    /** 规则：返回表注释，去掉首尾空白 */
    @Test
    void of_returnsTrimmedComment() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("pur_order"))).thenReturn(" 采购单 ");

        assertThat(tableComments.of("pur_order")).isEqualTo("采购单");
    }

    /** 规则：没写注释（或表不存在，to_regclass 给 NULL）返回 null，空白的注释也算没写 */
    @Test
    void of_noComment_returnsNull() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("a"))).thenReturn(null);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("b"))).thenReturn("  ");

        assertThat(tableComments.of("a")).isNull();
        assertThat(tableComments.of("b")).isNull();
    }

    /** 规则：查的时候数据库出错，返回 null（调用方说"其他数据"），不往外抛 */
    @Test
    void of_databaseError_returnsNull() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("pur_order")))
                .thenThrow(new DataAccessResourceFailureException("连接断了"));

        assertThat(tableComments.of("pur_order")).isNull();
    }
}
