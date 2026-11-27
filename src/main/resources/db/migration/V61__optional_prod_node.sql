-- V61：二次加工节点按单选（甲方 11-27 答 Q61：加工厂自己找人做印花、绣花、洗水，发成衣、不跟数量、费用含在加工费里，
-- "在加工单上多跟一个二次加工节点就够"）。
-- 不放进每张单都有的默认节点：加一个节点，所有单的开裁日期、要料日期都会往前挪。改成生产节点字典多一个"用法"：
-- 按单选的节点只有下单时勾了的单才有

-- 1. 节点用法字典（生产节点的文字值从这里选）：空或 ALWAYS = 每张单都有，OPTIONAL = 按单选
INSERT INTO sys_dict_type (code, name, remark)
SELECT 'node_usage', '节点用法', '生产节点的"用法"：每张单都有，还是改外发加工单时勾了才有'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE code = 'node_usage' AND deleted = false);

INSERT INTO sys_dict_data (type_code, label, value, sort)
SELECT 'node_usage', v.label, v.value, v.sort
FROM (VALUES ('每张单都有', 'ALWAYS', 1), ('按单选', 'OPTIONAL', 2)) AS v(label, value, sort)
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.type_code = 'node_usage' AND d.value = v.value AND d.deleted = false);

UPDATE sys_dict_type SET text_label = '用法', text_dict = 'node_usage'
WHERE code = 'prod_node' AND deleted = false AND text_label IS NULL AND text_dict IS NULL;

-- 2. 二次加工：排在车缝后面（和车缝同一个排序号，id 大的在后），默认 3 天，按单选
INSERT INTO sys_dict_data (type_code, label, value, sort, num_value, text_value)
SELECT 'prod_node', '二次加工', 'SECONDARY',
       COALESCE((SELECT sort FROM sys_dict_data WHERE type_code = 'prod_node' AND value = 'SEW' AND deleted = false LIMIT 1), 3),
       3, 'OPTIONAL'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE type_code = 'prod_node' AND value = 'SECONDARY' AND deleted = false);

-- 3. 生产单记这张单加了哪些按单选的节点
ALTER TABLE prd_order ADD COLUMN optional_nodes VARCHAR(200);
COMMENT ON COLUMN prd_order.optional_nodes IS '这张单加的按单选节点（字典 prod_node 里用法是 OPTIONAL 的 value，逗号分隔，如 SECONDARY 二次加工）；NULL = 没加。审核生成节点时和每张单都有的节点一起倒排';
