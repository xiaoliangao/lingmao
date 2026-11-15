package com.lingmao.scm.common.mybatis;

import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * JSONB 列和 Java 对象互转（JSONB 只放快照和设置值，架构 §6；领猫附加属性 legacy_attrs，数据库设计 §1.8）。实体上这样写：
 * <pre>
 * &#64;TableName(value = "bas_material", autoResultMap = true)
 * public class Material {
 *     &#64;TableField(typeHandler = JsonbTypeHandler.class)
 *     private Map&lt;String, String&gt; legacyAttrs;
 * }
 * </pre>
 * autoResultMap 不能少：BaseMapper 的查询靠它才用这个处理器读，不写的话读出来是 null。
 * 手写 SQL 查这一列时，XML 的 resultMap 里同样要写 typeHandler；Updates.replaceById 不用管（不是 null 的照常由 MyBatis-Plus 写）。
 * <p>
 * 为什么不直接用 MyBatis-Plus 的 Jackson3TypeHandler：它按字符串写（setString），PostgreSQL 不肯把 varchar 放进 jsonb 列。
 * 这里改成 setObject(…, Types.OTHER)，参数不带类型，由数据库按列的类型转。读不用改：jsonb 读出来就是 JSON 文本。
 * 泛型（Map&lt;String, String&gt;、List&lt;…&gt;）按字段声明的类型解析。
 */
public class JsonbTypeHandler extends Jackson3TypeHandler {

    public JsonbTypeHandler(Class<?> type) {
        super(type);
    }

    public JsonbTypeHandler(Class<?> type, Field field) {
        super(type, field);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, toJson(parameter), Types.OTHER);
    }
}
