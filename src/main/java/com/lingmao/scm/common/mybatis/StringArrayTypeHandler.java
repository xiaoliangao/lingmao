package com.lingmao.scm.common.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * VARCHAR[] 列和 List&lt;String&gt; 互转（人、供应商的品类线，RFC 013 §2）。实体上这样写：
 * <pre>
 * &#64;TableName(value = "sup_supplier", autoResultMap = true)
 * public class Supplier {
 *     &#64;TableField(typeHandler = StringArrayTypeHandler.class)
 *     private List&lt;String&gt; productLines;
 * }
 * </pre>
 * autoResultMap 不能少，理由同 {@link JsonbTypeHandler}。读出来是不可变的 List；NULL 读成空 List。
 * 不注册成全局处理器（不写 @MappedTypes）：那样所有 List 字段都会走它
 */
public class StringArrayTypeHandler extends BaseTypeHandler<List<String>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType) throws SQLException {
        ps.setArray(i, ps.getConnection().createArrayOf("varchar", parameter.toArray()));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            return List.copyOf(Arrays.asList((String[]) array.getArray()));
        } finally {
            array.free();
        }
    }
}
