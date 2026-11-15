package com.lingmao.scm.common.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.lingmao.scm.common.mybatis.Updates;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户 11-15 定：新业务表不用逻辑删除。这里钉住两件事，用 MyBatis-Plus 真正生成的 SQL 来看（不连数据库）：
 * 1. 全局配了 logic-delete-field: deleted（application.yaml 就是这么配的），继承 AuditEntity 的实体没有 deleted 字段，
 *    删除仍然是真的 DELETE，查询、修改也不带 deleted 条件；继承 BaseEntity 的 sys 实体照旧是逻辑删除。
 * 2. 保存的整体覆盖（Updates.replaceById）两种实体都能用：null 字段写成 NULL，审计列由自动填充写，
 *    创建人、创建时间和 NEVER 的列不写，逻辑删除列不写。
 */
class AuditEntityTest {

    private static MybatisConfiguration configuration;

    @BeforeAll
    static void buildMappers() {
        configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig global = GlobalConfigUtils.defaults();
        // 和 application.yaml 的 mybatis-plus.global-config.db-config 一样
        global.getDbConfig().setLogicDeleteField("deleted").setLogicDeleteValue("true").setLogicNotDeleteValue("false");
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.addMapper(NewThingMapper.class);
        configuration.addMapper(OldThingMapper.class);
    }

    /** 新表：全局的 logic-delete-field 对它不起作用，deleteById 是真的 DELETE */
    @Test
    void auditEntity_notLogicDelete_deleteIsRealDelete() {
        assertThat(TableInfoHelper.getTableInfo(NewThing.class).isWithLogicDelete()).isFalse();

        assertThat(sql(NewThingMapper.class, "deleteById", 7L)).isEqualTo("DELETE FROM biz_new_thing WHERE id=?");
        assertThat(sql(NewThingMapper.class, "selectById", 7L)).doesNotContain("deleted");
    }

    /** sys 表：继承 BaseEntity，照旧逻辑删除，deleteById 变成把 deleted 改成 true */
    @Test
    void baseEntity_logicDelete_deleteIsUpdate() {
        assertThat(TableInfoHelper.getTableInfo(OldThing.class).isWithLogicDelete()).isTrue();

        assertThat(sql(OldThingMapper.class, "deleteById", 7L))
                .startsWith("UPDATE sys_old_thing SET")
                .contains("deleted=true")
                .endsWith("WHERE id=? AND deleted=false");
    }

    /** 新表的整体覆盖：name 照常写，remark 是 null 写成 NULL，审计列由自动填充写，创建的两列和 NEVER 的 status 不写 */
    @Test
    void replaceById_auditEntity_sql() {
        NewThing thing = new NewThing();
        thing.setId(7L);
        thing.setName("新名字");

        String sql = sql(NewThingMapper.class, "update", updateParams(thing, Updates.replaceById(thing)));

        assertThat(sql).startsWith("UPDATE biz_new_thing SET")
                .contains("name=?", "update_by=?", "update_time=?", "remark = NULL")
                .doesNotContain("create_by", "create_time", "status", "deleted")
                .endsWith("WHERE (id = ?)");
    }

    /** sys 表的整体覆盖：一样的写法，条件里 MyBatis-Plus 自动加上 deleted=false，SET 里没有 deleted */
    @Test
    void replaceById_baseEntity_sql() {
        OldThing thing = new OldThing();
        thing.setId(7L);
        thing.setName("新名字");

        String sql = sql(OldThingMapper.class, "update", updateParams(thing, Updates.replaceById(thing)));

        assertThat(sql).contains("name=?", "update_by=?", "update_time=?", "remark = NULL")
                .doesNotContain("create_by", "create_time", "deleted=true")
                .contains("deleted=false")
                .contains("(id = ?)");
    }

    private static Map<String, Object> updateParams(Object entity, Object wrapper) {
        Map<String, Object> params = new HashMap<>();
        params.put(Constants.ENTITY, entity);
        params.put(Constants.WRAPPER, wrapper);
        return params;
    }

    /** MyBatis-Plus 给这个 Mapper 方法生成的 SQL，空白合并成一个空格 */
    private static String sql(Class<?> mapper, String method, Object param) {
        return configuration.getMappedStatement(mapper.getName() + "." + method)
                .getBoundSql(param).getSql().replaceAll("\\s+", " ").strip();
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("biz_new_thing")
    static class NewThing extends AuditEntity {
        private String name;
        private String remark;
        @TableField(updateStrategy = FieldStrategy.NEVER)
        private String status;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("sys_old_thing")
    static class OldThing extends BaseEntity {
        private String name;
        private String remark;
    }

    interface NewThingMapper extends BaseMapper<NewThing> {
    }

    interface OldThingMapper extends BaseMapper<OldThing> {
    }
}
