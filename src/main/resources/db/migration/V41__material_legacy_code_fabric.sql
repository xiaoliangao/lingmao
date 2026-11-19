-- V41：甲方 11-18 晚的答复（RFC 012 §4.2、§5.1）：物料、物料 SKU 加领猫编码；物料加面料功能、是否带吊牌

-- 1. 领猫编码（Q05）：系统里的编码按新规则发，领猫的老编码存这里做对应，按它也能搜到、能对上期初库存
ALTER TABLE bas_material ADD COLUMN legacy_code VARCHAR(32);
ALTER TABLE bas_material_sku ADD COLUMN legacy_code VARCHAR(64);
COMMENT ON COLUMN bas_material.legacy_code IS '领猫编码：从领猫导入的物料原来的物料编码，只做对应；系统编码按新规则另发（RFC 012 §4.2）';
COMMENT ON COLUMN bas_material_sku.legacy_code IS '领猫编码：从领猫导入的 SKU 原来的物料SKU编码，只做对应';
CREATE UNIQUE INDEX uk_bas_material_legacy_code ON bas_material (legacy_code) WHERE legacy_code IS NOT NULL;
CREATE UNIQUE INDEX uk_bas_material_sku_legacy_code ON bas_material_sku (legacy_code) WHERE legacy_code IS NOT NULL;

-- 以前导入的物料照用的是领猫编码：领猫编码就是它的编码（操作记录里有 import 的就是导入的）
UPDATE bas_material m SET legacy_code = m.code
WHERE EXISTS (SELECT 1 FROM biz_doc_log l WHERE l.doc_type = 'MATERIAL' AND l.doc_id = m.id AND l.action = 'import');
UPDATE bas_material_sku k SET legacy_code = k.code
FROM bas_material m
WHERE m.id = k.material_id AND m.legacy_code IS NOT NULL;

-- 2. 面料功能、是否带吊牌：原来是领猫附加属性（legacy_attrs，只读），改成正式字段，已经存在附加属性里的挪过来
ALTER TABLE bas_material ADD COLUMN fabric_function VARCHAR(255);
ALTER TABLE bas_material ADD COLUMN with_tag BOOLEAN;
COMMENT ON COLUMN bas_material.fabric_function IS '面料功能，填文字，如"防水、抗皱"（领猫"面料功能"）；面料、里料才填';
COMMENT ON COLUMN bas_material.with_tag IS '是否带吊牌，空 = 没填（领猫"是否带吊牌"）；只记录，不参与规则';

UPDATE bas_material
SET fabric_function = left(NULLIF(btrim(legacy_attrs ->> '面料功能'), ''), 255),
    with_tag = CASE btrim(legacy_attrs ->> '是否带吊牌') WHEN '是' THEN true WHEN '否' THEN false END,
    legacy_attrs = NULLIF(legacy_attrs - '面料功能' - '是否带吊牌', '{}'::jsonb)
WHERE jsonb_exists_any(legacy_attrs, ARRAY['面料功能', '是否带吊牌']);
