package com.lingmao.scm.common.exception;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.entity.DictType;
import com.lingmao.scm.module.system.mapper.DictTypeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 数据库约束的报错怎么变成返回（RFC 001 §5.2、§4.3）。GlobalExceptionHandlerTest 里喂的是手写的报错文字，
 * 这里用 PostgreSQL 真正抛出来的异常，钉住"认得出报错原文、查得到表注释"。
 * 现有的表还没有外键（sys_ 表沿用 V5 不补），所以临时建两张带外键、带中文表注释的表，测完删掉
 */
@IntegrationTest
class GlobalExceptionHandlerIT {

    @Autowired
    private GlobalExceptionHandler handler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DictTypeMapper dictTypeMapper;

    @BeforeEach
    void createTables() {
        jdbcTemplate.execute("""
                CREATE TABLE it_fk_parent (id BIGINT PRIMARY KEY);
                CREATE TABLE it_fk_child (id BIGINT PRIMARY KEY, parent_id BIGINT NOT NULL REFERENCES it_fk_parent (id));
                COMMENT ON TABLE it_fk_parent IS '测试物料';
                COMMENT ON TABLE it_fk_child IS '测试采购单';
                INSERT INTO it_fk_parent VALUES (1);
                INSERT INTO it_fk_child VALUES (1, 1);
                """);
    }

    @AfterEach
    void dropTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS it_fk_child, it_fk_parent");
    }

    /** 规则：删被引用的数据，外键拦住，返回 1005，提示里是引用方的中文表注释 */
    @Test
    void deleteReferenced_returns1005WithTableComment() {
        DataIntegrityViolationException e = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM it_fk_parent WHERE id = 1"));

        R<Void> r = handler.handleDataIntegrity(e, request());

        assertThat(r.getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM);
        assertThat(r.getMsg()).isEqualTo("已被测试采购单引用，不能删除，只能停用");
    }

    /** 规则：引用方没写表注释，说"其他数据"，不把英文表名给用户看 */
    @Test
    void deleteReferenced_noComment_saysOtherData() {
        jdbcTemplate.execute("COMMENT ON TABLE it_fk_child IS NULL");
        DataIntegrityViolationException e = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM it_fk_parent WHERE id = 1"));

        R<Void> r = handler.handleDataIntegrity(e, request());

        assertThat(r.getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM);
        assertThat(r.getMsg()).isEqualTo("已被其他数据引用，不能删除，只能停用");
    }

    /** 规则：插入时引用的数据不存在（页面开着时被别人删了），返回 1000 让用户刷新，不是 1005 */
    @Test
    void insertMissingReference_returns1000() {
        DataIntegrityViolationException e = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("INSERT INTO it_fk_child VALUES (2, 999)"));

        R<Void> r = handler.handleDataIntegrity(e, request());

        assertThat(r.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
        assertThat(r.getMsg()).isEqualTo("关联的数据不存在，可能刚被别人删除了，请刷新后再试");
    }

    /**
     * 规则：查重之后、插入之前别人刚好建了同样的编码，唯一索引拦住，返回 1000 不是 500。
     * 走 MyBatis 的插入（和 Service 一样），确认它抛的是 DuplicateKeyException、会落到对应的处理方法
     */
    @Test
    void duplicateKey_returns1000() {
        String code = "it_" + UUID.randomUUID().toString().substring(0, 8);
        dictTypeMapper.insert(dictType(code));
        DuplicateKeyException e = catchThrowableOfType(DuplicateKeyException.class,
                () -> dictTypeMapper.insert(dictType(code)));

        R<Void> r = handler.handleDuplicateKey(e);

        assertThat(r.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
    }

    private static DictType dictType(String code) {
        DictType type = new DictType();
        type.setCode(code);
        type.setName("测试");
        type.setStatus(1);
        return type;
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("DELETE", "/api/test");
    }
}
