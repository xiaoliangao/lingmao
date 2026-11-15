package com.lingmao.scm.common.mybatis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 表的中文名：迁移里 COMMENT ON TABLE pur_order IS '采购单' 写的那个注释。
 * 删除被外键拦住时，GlobalExceptionHandler 用它把报错里的表名翻成"已被采购单引用，不能删除，只能停用"。
 * 所以每张业务表的迁移都要写表注释，写成用户看得懂的单据或资料名（"采购单"，不是"采购单主表"）。
 * <p>
 * 不缓存：只在删除被拦住时查一次，很少发生；注释只随迁移变，迁移要重启应用，缓存了也没坏处，只是用不着。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TableComments {

    private final JdbcTemplate jdbcTemplate;

    /** 表的注释；没写注释、表不存在、查的时候出错，都返回 null（调用方用通用的说法） */
    public String of(String tableName) {
        try {
            // to_regclass：表不存在时返回 NULL，不报错；按 search_path 找，和报错里不带 schema 的表名对得上
            String comment = jdbcTemplate.queryForObject(
                    "SELECT obj_description(to_regclass(?), 'pg_class')", String.class, tableName);
            return StringUtils.hasText(comment) ? comment.trim() : null;
        } catch (DataAccessException e) {
            log.warn("查表注释失败: {}，原因: {}", tableName, e.getMessage());
            return null;
        }
    }
}
