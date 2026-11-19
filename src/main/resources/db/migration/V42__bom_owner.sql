-- RFC 012 §3：物料清单研发和生产分工（甲方 11-18 答 Q49 选 B：面辅料归研发，耗材归生产）。
-- 按物料类型分：字典 material_type 每一项的 text_value 写 DEV 或 PROD（空 = DEV）。

-- 1. 字典项能带一个"从另一个字典里选的值"：类型上记这一列叫什么、从哪个字典选。字典页按它多一列、编辑时给下拉。
--    text_dict 是程序认的，只在迁移里设；text_label 空 = 这个字典的 text_value 不在字典页上编辑（如生产单常用条款以前的用法）
ALTER TABLE sys_dict_type ADD COLUMN text_label VARCHAR(32);
ALTER TABLE sys_dict_type ADD COLUMN text_dict VARCHAR(64);
COMMENT ON COLUMN sys_dict_type.text_label IS '字典项文字列的名字，如 物料清单谁填；空 = 字典页不编辑 text_value';
COMMENT ON COLUMN sys_dict_type.text_dict IS 'text_value 从哪个字典里选（存那个字典的 value）；空 = 随便填';

-- 2. 谁填：研发、生产。程序按 value 认，内置
INSERT INTO sys_dict_type (code, name, remark)
VALUES ('bom_owner', '物料清单谁填', '物料类型的"物料清单谁填"从这里选（RFC 012 §3）');

INSERT INTO sys_dict_data (type_code, label, value, sort, builtin)
VALUES ('bom_owner', '研发', 'DEV',  1, true),
       ('bom_owner', '生产', 'PROD', 2, true);

-- 3. 物料类型：包材归生产，其余归研发（甲方没说哪些算耗材之前的默认，PRD §12）
UPDATE sys_dict_type SET text_label = '物料清单谁填', text_dict = 'bom_owner'
WHERE code = 'material_type' AND deleted = false;

UPDATE sys_dict_data SET text_value = CASE value WHEN 'PACKING' THEN 'PROD' ELSE 'DEV' END
WHERE type_code = 'material_type' AND deleted = false;

-- 4. 两个按钮权限，挂在"资料包"页面下，跟着"编辑"排
INSERT INTO sys_menu (parent_id, name, type, perms, sort, admin_only)
SELECT e.parent_id, v.name, 3, v.perms, v.sort, false
FROM sys_menu e
CROSS JOIN (VALUES ('填物料清单研发的行（面辅料）', 'techpack:bom:dev',  6),
                   ('填物料清单生产的行（耗材）',   'techpack:bom:prod', 7)) AS v(name, perms, sort)
WHERE e.perms = 'techpack:pack:edit' AND e.deleted = false
  AND NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.perms = v.perms AND x.deleted = false);

-- 5. 授权：开发、开发主管填研发的行；生产跟单填生产的行，要改资料包草稿所以加"编辑"；
--    别的已经能改资料包的角色（管理员自己建的）两样都给，和现在能做的一样
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, b.id
FROM sys_role_menu rm
JOIN sys_menu e ON e.id = rm.menu_id AND e.perms = 'techpack:pack:edit' AND e.deleted = false
JOIN sys_role r ON r.id = rm.role_id AND r.deleted = false
JOIN sys_menu b ON b.perms IN ('techpack:bom:dev', 'techpack:bom:prod') AND b.deleted = false
WHERE b.perms = 'techpack:bom:dev' OR r.code NOT IN ('dev', 'dev_lead')
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.perms IN ('techpack:pack:edit', 'techpack:bom:prod') AND m.deleted = false
WHERE r.code = 'merchandiser' AND r.deleted = false
ON CONFLICT DO NOTHING;
