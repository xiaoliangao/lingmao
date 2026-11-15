package com.lingmao.scm.common.mybatis;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.entity.AuditEntity;
import com.lingmao.scm.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 保存是整体覆盖（RFC 001 §4.2，bug 005）：replaceById 给 null 字段补 SET 列 = NULL，哪些列不补 */
class UpdatesTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Supplier.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DictLike.class);
    }

    /**
     * 规则：可编辑的 null 字段（备注、电话）都写成 NULL；审计列（自动填充）、NEVER 的列（由动作改的状态）、
     * ALWAYS 的列（MyBatis-Plus 自己会写）、基本类型的列都不补；条件是 id = ?
     */
    @Test
    void replaceById_nullEditableFieldsOnly() {
        Supplier supplier = new Supplier();
        supplier.setId(7L);
        supplier.setName("供应商甲");

        UpdateWrapper<Supplier> wrapper = Updates.replaceById(supplier);

        assertThat(Arrays.asList(wrapper.getSqlSet().split(","))).containsExactlyInAnyOrder("remark = NULL", "phone = NULL");
        assertThat(wrapper.getSqlSegment()).isEqualTo("(id = #{ew.paramNameValuePairs.MPGENVAL1})");
        assertThat(wrapper.getParamNameValuePairs()).containsEntry("MPGENVAL1", 7L);
    }

    /** 规则：所有可编辑字段都有值时，不补任何 SET，只有条件 */
    @Test
    void replaceById_noNullFields_noExtraSet() {
        Supplier supplier = new Supplier();
        supplier.setId(7L);
        supplier.setName("供应商甲");
        supplier.setRemark("备注");
        supplier.setPhone("010-1234");

        assertThat(Updates.replaceById(supplier).getSqlSet()).isNull();
    }

    /** 规则：带逻辑删除的 sys 实体也能用，deleted 是 null 也不补（不然保存会把它写成 NULL） */
    @Test
    void replaceById_baseEntity_skipsLogicDeleteColumn() {
        DictLike dict = new DictLike();
        dict.setId(3L);

        assertThat(Updates.replaceById(dict).getSqlSet()).isEqualTo("remark = NULL");
    }

    /** 规则：没有 id、没有表信息，都是程序写错了，直接抛 */
    @Test
    void replaceById_noIdOrNoTableInfo_throws() {
        assertThatThrownBy(() -> Updates.replaceById(new Supplier()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有 id");
        assertThatThrownBy(() -> Updates.replaceById(new NoMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有")
                .hasMessageContaining("表信息");
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("biz_supplier")
    static class Supplier extends AuditEntity {
        private String name;
        private String remark;
        private String phone;
        @TableField(updateStrategy = FieldStrategy.NEVER)
        private Integer status;
        @TableField(updateStrategy = FieldStrategy.ALWAYS)
        private String memo;
        private int version;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("sys_dict_like")
    static class DictLike extends BaseEntity {
        private String remark;
    }

    /** 没有建表信息的类 */
    @Data
    static class NoMapper {
        private Long id;
    }
}
