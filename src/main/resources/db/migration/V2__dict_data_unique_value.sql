-- V2：同一个字典类型下，存储值 value 不能重复。
-- 只约束未删除的行（部分索引），这样删掉的 value 以后还能重新建。
CREATE UNIQUE INDEX uk_sys_dict_data_type_value ON sys_dict_data (type_code, value) WHERE deleted = false;