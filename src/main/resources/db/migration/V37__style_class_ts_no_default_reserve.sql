-- V37：甲方 11-18 的两条答复（PRD v0.22）。
-- 1. 款式大类（Q28）：服装分 TS 通勤裤、FS 非通勤裤，XZ 鞋子不变。加 TS；FS 原来叫"服饰"，改名。
--    管理员已经自己加过 TS、改过 FS 名字的不动。值建好不能改（款号开头就是它），只改名字。
INSERT INTO sys_dict_data (type_code, label, value, sort)
SELECT 'style_class', '通勤裤', 'TS', 2
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'style_class' AND value = 'TS' AND NOT deleted);

UPDATE sys_dict_data SET label = '非通勤裤', update_time = now()
WHERE type_code = 'style_class' AND value = 'FS' AND label = '服饰' AND NOT deleted;

UPDATE sys_dict_data SET sort = 3, update_time = now()
WHERE type_code = 'style_class' AND value = 'XZ' AND sort = 2 AND NOT deleted;

-- 2. 备料比不再默认 3%（Q18、Q31）：由生产评估后自己填，没填按 0 算。撤回 V24 把物料类型改成 3 的那一步：
--    还是 3 的改回 0；已经改成别的数的不动。
UPDATE sys_dict_data SET num_value = 0, update_time = now()
WHERE type_code = 'material_type' AND num_value = 3 AND NOT deleted;
