-- V48：甲方 11-21 的答复（PRD v0.32 §3.1、§3.2、§6.1.5、§6.4.3、§6.12）。
-- 只动预置模板的授权和字典的默认值；管理员在角色、字典里改过的，能不碰就不碰（见各段）

-- 1. 角色模板加权限：
--    开发全员能看成本（开发主管和开发就一样了，模板留着不删）；
--    生产经理审需求订单、采购单，要看得到金额：加看订单销售价、看成本；
--    生产跟单、生产经理看供应商联系方式（找工厂用），银行信息不给
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM (VALUES ('dev',          'techpack:cost:query'),
             ('prod_manager', 'order:price:query'),
             ('prod_manager', 'techpack:cost:query'),
             ('merchandiser', 'supplier:supplier:contact'),
             ('prod_manager', 'supplier:supplier:contact')) AS g(code, perm)
JOIN sys_role r ON r.code = g.code AND r.deleted = false
JOIN sys_menu m ON m.perms = g.perm AND m.deleted = false AND m.admin_only = false
ON CONFLICT DO NOTHING;

-- 2. 新角色模板"业务主管"：业务的权限，数据范围全部（甲方：管理人员、业务主管、生产经理都看全部）。
--    已经有同编码的角色（甲方手建过）就不动它，授权照样加
INSERT INTO sys_role (code, name, sort, data_scope, remark)
VALUES ('sales_lead', '业务主管', 10, 'ALL', '预置模板，权限和数据范围可以改')
ON CONFLICT (code) WHERE deleted = false DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT sl.id, rm.menu_id
FROM sys_role sl
JOIN sys_role s ON s.code = 'sales' AND s.deleted = false
JOIN sys_role_menu rm ON rm.role_id = s.id
WHERE sl.code = 'sales_lead' AND sl.deleted = false
ON CONFLICT DO NOTHING;

-- 授了按钮的，再授它的各级上级（同 V16 第 4 步），只算这一次动过的角色
WITH RECURSIVE up (role_id, menu_id) AS (
    SELECT rm.role_id, m.parent_id
    FROM sys_role_menu rm
    JOIN sys_role r ON r.id = rm.role_id AND r.deleted = false
     AND r.code IN ('dev', 'prod_manager', 'merchandiser', 'sales_lead')
    JOIN sys_menu m ON m.id = rm.menu_id
    WHERE m.parent_id <> 0
    UNION
    SELECT up.role_id, m.parent_id
    FROM up
    JOIN sys_menu m ON m.id = up.menu_id
    WHERE m.parent_id <> 0
)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role_id, menu_id FROM up
ON CONFLICT DO NOTHING;

-- 3. 成品库存以聚水潭为准（Q44）：成品的三个页面、按钮和顶层目录"成品"从所有角色收回。
--    代码、表、菜单都留着，以后要用在角色里勾上就行；超级管理员不看授权，照样看得到。
--    回货不会自动生成成品入库单，首页"回货待入成品仓"按 fg:doc:add 出，收回以后也不出了
DELETE FROM sys_role_menu rm
USING sys_menu m
WHERE m.id = rm.menu_id
  AND (m.perms LIKE 'fg:%'
       OR (m.parent_id = 0 AND m.type = 1 AND m.path = '/fg'));

-- 4. 物料清单谁填（RFC 012 §3.1）：主料和工艺开发填，辅料生产填，不加耗材类型。
--    面料、里料、工艺、鞋底归开发；辅料、标签吊牌、配件归生产（包材 V42 起就归生产）。
--    只改还是默认值（DEV 或空）的，管理员已经改成生产的不用动
UPDATE sys_dict_data SET text_value = 'PROD'
WHERE type_code = 'material_type' AND deleted = false
  AND value IN ('TRIM', 'LABEL', 'ACCESSORY')
  AND COALESCE(text_value, 'DEV') = 'DEV';

-- 甲方叫"开发"不叫"研发"；管理员改过名字的不动
UPDATE sys_dict_data SET label = '开发'
WHERE type_code = 'bom_owner' AND value = 'DEV' AND label = '研发' AND deleted = false;

-- 两个按钮权限的名字跟着改（角色页里勾的就是它）；管理员改过名字的不动
UPDATE sys_menu SET name = '填物料清单开发的行（主料和工艺）'
WHERE perms = 'techpack:bom:dev' AND name = '填物料清单研发的行（面辅料）' AND deleted = false;
UPDATE sys_menu SET name = '填物料清单生产的行（辅料）'
WHERE perms = 'techpack:bom:prod' AND name = '填物料清单生产的行（耗材）' AND deleted = false;

-- 5. 功能吊牌（RFC 012 §5.1）：甲方说"是否带吊牌"指面料带不带功能性吊牌，是的话要填吊牌名称，可能几个
ALTER TABLE bas_material ADD COLUMN tag_names VARCHAR(255);
ALTER TABLE bas_material ADD CONSTRAINT ck_bas_material_tag_names CHECK (tag_names IS NULL OR with_tag IS TRUE);
COMMENT ON COLUMN bas_material.with_tag IS '带不带功能性吊牌（甲方 11-21 说明），空 = 没填（领猫"是否带吊牌"）；只记录，不参与规则';
COMMENT ON COLUMN bas_material.tag_names IS '功能吊牌的名称，几个用顿号隔开，如 coolmax 吊牌；with_tag 是 true 才有';
