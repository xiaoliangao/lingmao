package com.lingmao.scm.common.mybatis;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

/**
 * 保存是整体覆盖（RFC 001 §4.2）：修改接口收到的是这条记录所有可编辑字段的值，没传或传 null 就是清空。
 * <p>
 * MyBatis-Plus 的 updateById 跳过 null 字段，传 null 等于"不改"，旧备注清不掉（bug 005）。
 * 不改全局策略（改了以后"改状态"那种 update(new Xxx(), 条件) 会把所有字段写成 NULL），只在保存时用这里补一句：
 * <pre>
 * DictType entity = getById(id);                          // 不存在抛 404
 * BeanUtils.copyProperties(req, entity);                  // 请求里的 null 也拷过去
 * dictTypeMapper.update(entity, Updates.replaceById(entity));
 * </pre>
 * 实体里不是 null 的字段照常由 MyBatis-Plus 写；是 null 的，这里给每个加一条 SET 列 = NULL。条件是 id = ?，
 * 有逻辑删除列的实体 MyBatis-Plus 还会自动加 AND deleted = false，没有的就只按 id。
 * <p>
 * 不写的列：主键；自动填充的审计列（AuditMetaObjectHandler 管）；逻辑删除列；标了 updateStrategy = NEVER 的
 * "由动作改"的列（状态、审核人、累计数……，RFC 001 §4.3），保存永远不碰它们，只有动作的条件更新能改；
 * 标了 ALWAYS 的列 MyBatis-Plus 本来就会写（null 也写），这里再加一条就重复了。
 */
public final class Updates {

    private Updates() {
    }

    /** 给 mapper.update(entity, 这里返回的条件) 用。实体必须已经有 id（先查出来再改） */
    public static <T> UpdateWrapper<T> replaceById(T entity) {
        TableInfo table = TableInfoHelper.getTableInfo(entity.getClass());
        if (table == null) {
            throw new IllegalStateException("没有 " + entity.getClass().getName() + " 的表信息：它有对应的 Mapper 吗？"
                    + "（单元测试里要先 TableInfoHelper.initTableInfo，见 RoleServiceImplTest）");
        }
        Object id = table.getPropertyValue(entity, table.getKeyProperty());
        if (id == null) {
            throw new IllegalArgumentException("replaceById 的实体没有 id：" + entity.getClass().getSimpleName());
        }
        UpdateWrapper<T> wrapper = new UpdateWrapper<>();
        for (TableFieldInfo field : table.getFieldList()) {
            if (writesNullItself(field)) {
                continue;
            }
            if (table.getPropertyValue(entity, field.getProperty()) == null) {
                // 写成字面量 NULL 而不是参数：不用操心 null 参数的类型，日志里的 SQL 也一眼看得懂
                wrapper.setSql(field.getColumn() + " = NULL");
            }
        }
        return wrapper.eq(table.getKeyColumn(), id);
    }

    /** 这些列要么不该由保存来改，要么 MyBatis-Plus 自己会写：都不用这里补 SET 列 = NULL */
    private static boolean writesNullItself(TableFieldInfo field) {
        return field.isLogicDelete()
                || field.isWithInsertFill()
                || field.isWithUpdateFill()
                || field.getUpdateStrategy() == FieldStrategy.NEVER
                || field.getUpdateStrategy() == FieldStrategy.ALWAYS
                || field.isPrimitive();
    }
}
