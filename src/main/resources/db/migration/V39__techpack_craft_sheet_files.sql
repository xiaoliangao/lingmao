-- V39：资料包改版（用户 11-18 定，PRD v0.23、RFC 011）。
-- 工艺单就是甲方的 Excel，上传挂在资料包版本上（biz_attachment，TECH_PACK，category = CRAFT_SHEET / PATTERN / BOM_SOURCE）；
-- 尺寸指示、工艺指示分段、工序工价、工艺标准、导出 PDF 去掉；加工费改成资料包上的一个数。
-- 这几张表里只有开发、测试数据：试用环境还没建过资料包的表，甲方没有数据在里面，所以直接删表，不留空表（CLAUDE.md"只增不删"从有甲方真实数据算）。

-- 1. 加工费：按外发算核价时用，元/件；空 = 取价格档案里默认加工厂的清加工、再取半包现价
ALTER TABLE tp_tech_pack ADD COLUMN labor_price NUMERIC(14,4);
COMMENT ON COLUMN tp_tech_pack.labor_price IS '加工费，元/件，按外发算时用；空时取价格档案里默认加工厂清加工、再半包的现价';
ALTER TABLE tp_tech_pack DROP COLUMN packing_mode;

-- 2. 尺寸指示、工艺指示、工序工价的表。先删子表：外键是 RESTRICT
DROP TABLE tp_process_image;
DROP TABLE tp_process;
DROP TABLE tp_craft_section;
DROP TABLE tp_spec_value;
DROP TABLE tp_spec_point;

-- 3. 工艺指示的插图（category 是段代码）、工艺标准的文件：只删引用，没人引用的文件由定时任务清掉（架构 §8）
DELETE FROM biz_attachment WHERE doc_type = 'TECH_PACK' AND category NOT IN ('CRAFT_SHEET', 'PATTERN', 'BOM_SOURCE');
DELETE FROM biz_attachment WHERE doc_type = 'CRAFT_STANDARD';
COMMENT ON COLUMN biz_attachment.category IS '分组：IMAGE 图片 / FILE 文件；款式设计附件 DESIGN；资料包 CRAFT_SHEET 工艺单 / PATTERN 纸样和其他 / BOM_SOURCE 导入的 BOM 表';

-- 4. 工艺标准的两项设置（工艺指示默认文字、默认包装方式）。sys_config 表留着给以后的设置（架构 §3.9）
DELETE FROM sys_config WHERE config_key LIKE 'techpack.%';

-- 5. 工艺标准的菜单（V17 建、V18 藏起来）和"修改"按钮，连同各角色的授权。sys_ 表逻辑删除
DELETE FROM sys_role_menu
WHERE menu_id IN (SELECT id FROM sys_menu WHERE deleted = false
                  AND (path = '/craft-standard' OR perms LIKE 'techpack:standard:%'));
UPDATE sys_menu SET deleted = true, update_time = now()
WHERE deleted = false AND perms LIKE 'techpack:standard:%' AND type = 3;
UPDATE sys_menu SET deleted = true, update_time = now()
WHERE deleted = false AND path = '/craft-standard';

-- 6. 只给上面那几块用的字典：工序类别、尺寸部位（带默认测量方法）、包装方式
UPDATE sys_dict_data SET deleted = true, update_time = now()
WHERE deleted = false AND type_code IN ('process_category', 'size_point', 'packing_mode');
UPDATE sys_dict_type SET deleted = true, update_time = now()
WHERE deleted = false AND code IN ('process_category', 'size_point', 'packing_mode');
