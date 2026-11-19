-- V47：甲方 11-19 答 RFC 013 §7 第 2 条：百货的大类是 BH（款号开头 BH），归百货线。
-- 管理员已经自己在字典页加过 BH 的不动（名字、品类线照他填的）。
INSERT INTO sys_dict_data (type_code, label, value, sort, text_value)
SELECT 'style_class', '百货', 'BH', 4, 'GENERAL'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'style_class' AND value = 'BH' AND NOT deleted);
