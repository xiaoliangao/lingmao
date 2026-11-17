-- 本机开发库的示例主数据：只给 dev/compose.yml 起的开发库 scm 用，不是迁移，不进部署包，不往试用环境、正式环境跑。
--
-- 为什么有它：pnpm dev:mock 的假订单、生产单、资料包、出入库、采购单（web/src/mock/）写死了款式 1（FS2611150101）、
-- 颜色 1、2，物料 1-4，供应商 1-4，仓库 1-3，主体 1。这些主数据不走假接口、走真后端，开发库是空的，
-- 在假单据上点款号就是"这个款式不存在"，点物料、供应商也一样。这里按假数据用的 id 把它们建出来。
-- 各假数据文件之间有几处 id 本来就对不上（藏青黑 M 码的 SKU，订单里是 21、生产单里是 22），以订单的为准。
--
-- 用法（开发库起着的时候）：
--   docker exec -i scm-dev-postgres psql -U scm -d scm -v ON_ERROR_STOP=1 < dev/seed-demo.sql
-- 跑几遍都一样：已经有的跳过（ON CONFLICT DO NOTHING），最后把各表的自增调到最大 id 之后，之后页面上新建的接着往下排。
-- 名字都是编的（示例服饰、示例面料厂……），不放真实的企业、价格、供应商。

BEGIN;

-- 主体
INSERT INTO bas_company (id, code, name, short_name) OVERRIDING SYSTEM VALUE VALUES
    (1, 'C01', '示例服饰有限公司', '示例服饰')
ON CONFLICT DO NOTHING;

-- 字典 brand 没有预置值（等甲方给），款式的品牌必填
INSERT INTO sys_dict_data (type_code, label, value, sort) VALUES ('brand', '示例品牌', 'DEMO', 1)
ON CONFLICT DO NOTHING;

-- 颜色
INSERT INTO bas_color (id, code, name, family, hex) OVERRIDING SYSTEM VALUE VALUES
    (1, 'KQ', '卡其', NULL, '#c3b091'),
    (2, 'BK', '藏青黑', NULL, '#23272e'),
    (3, 'BR', '棕', NULL, '#6b4a2b')
ON CONFLICT DO NOTHING;

-- 尺码组
INSERT INTO bas_size_group (id, name, base_size) OVERRIDING SYSTEM VALUE VALUES (1, '女装 S-XL', 'M')
ON CONFLICT DO NOTHING;
INSERT INTO bas_size_group_item (id, size_group_id, seq, size_code) OVERRIDING SYSTEM VALUE VALUES
    (1, 1, 1, 'S'), (2, 1, 2, 'M'), (3, 1, 3, 'L'), (4, 1, 4, 'XL')
ON CONFLICT DO NOTHING;

-- 品类（款式用，三级）
INSERT INTO bas_category (id, parent_id, name, level, sort) OVERRIDING SYSTEM VALUE VALUES
    (1, NULL, '女装', 1, 1),
    (2, 1, '外套', 2, 1),
    (3, 2, '风衣', 3, 1)
ON CONFLICT DO NOTHING;

-- 物料分类：一级没有编码，二级的编码就是物料编码的开头
INSERT INTO bas_material_category (id, parent_id, code, name, level, sort) OVERRIDING SYSTEM VALUE VALUES
    (1, NULL, NULL, '面料', 1, 1),
    (2, 1, 'ML', '梭织面料', 2, 1),
    (3, NULL, NULL, '辅料', 1, 2),
    (4, 3, 'FL', '纽扣拉链', 2, 1),
    (5, 3, 'FZ', '缝纫线', 2, 2)
ON CONFLICT DO NOTHING;

-- 供应商：1、2 供料，3、4 做衣服（成衣厂在页面上建时自动有工厂仓，这里手建仓库 3、4）
INSERT INTO sup_supplier (id, code, name, short_name, status, settle_method, credit_days, invoice_type, tax_pct, buyer_id)
OVERRIDING SYSTEM VALUE VALUES
    (1, 'GYS0001', '示例面料厂', '示例面料厂', 'ACTIVE', 'MONTHLY', 30, 'SPECIAL', 13, 1),
    (2, 'GYS0002', '示例辅料厂', '示例辅料厂', 'ACTIVE', 'MONTHLY', 30, 'GENERAL', 3, 1),
    (3, 'GYS0003', '示例制衣一厂', '示例制衣一厂', 'ACTIVE', 'MONTHLY', 60, 'SPECIAL', 13, 1),
    (4, 'GYS0004', '示例制衣二厂', '示例制衣二厂', 'ACTIVE', 'MONTHLY', 60, 'SPECIAL', 13, 1)
ON CONFLICT DO NOTHING;
INSERT INTO sup_supplier_category (supplier_id, category) VALUES
    (1, 'FABRIC'), (2, 'TRIM'), (3, 'GARMENT'), (4, 'GARMENT')
ON CONFLICT DO NOTHING;

-- 仓库
INSERT INTO bas_warehouse (id, code, name, type, factory_id, address) OVERRIDING SYSTEM VALUE VALUES
    (1, 'CK01', '总公司面辅料一仓', 'MATERIAL', NULL, '示例市示例区仓储路 1 号'),
    (2, 'CK02', '总公司成品一仓', 'PRODUCT', NULL, '示例市示例区仓储路 1 号'),
    (3, 'CK03', '示例制衣一厂工厂仓', 'FACTORY', 3, NULL),
    (4, 'CK04', '示例制衣二厂工厂仓', 'FACTORY', 4, NULL)
ON CONFLICT DO NOTHING;

-- 物料和 SKU（单价按采购单位）
INSERT INTO bas_material (id, code, name, category_id, material_type, stock_unit, purchase_unit, conversion_factor, width_cm,
                          weight_gsm, composition, default_supplier_id, ref_price, moq, min_pack_qty, lead_days,
                          default_source, default_loss_pct) OVERRIDING SYSTEM VALUE VALUES
    (1, 'ML0001', '220g 精梳斜纹棉布', 2, 'FABRIC', 'METER', 'METER', 1, 150, 220, '100% 棉', 1, 18.5, 100, 1, 7, 'PURCHASE', 3),
    (2, 'FL0008', '20mm 复古牛角纹树脂四眼扣', 4, 'TRIM', 'BEAD', 'BEAD', 1, NULL, NULL, NULL, 2, 0.35, 1000, 100, 5, 'PURCHASE', 2),
    (3, 'FL0012', '5 号树脂拉链 60cm', 4, 'TRIM', 'STRIP', 'STRIP', 1, NULL, NULL, NULL, 2, 2.2, 200, 50, 5, 'PURCHASE', 1),
    (4, 'FZ0001', '涤纶缝纫线 40S/2', 5, 'TRIM', 'ROLL', 'ROLL', 1, NULL, NULL, '100% 涤纶', 2, 6, 10, 1, 3, 'FACTORY', 0)
ON CONFLICT DO NOTHING;
INSERT INTO bas_material_sku (id, material_id, code, color_id, spec, spec_code, price) OVERRIDING SYSTEM VALUE VALUES
    (101, 1, 'ML0001-KQ', 1, '150cm', NULL, NULL),
    (102, 1, 'ML0001-BK', 2, '150cm', NULL, NULL),
    (201, 2, 'FL0008-BR-20MM', 3, '20mm', '20MM', NULL),
    (301, 3, 'FL0012-BK', 2, '60cm', NULL, 2.4),
    (401, 4, 'FZ0001', NULL, NULL, NULL, NULL)
ON CONFLICT DO NOTHING;

-- 款式：两个颜色 × 四个尺码，已启用；资料包状态由资料包生效时回写，这里是 NONE
INSERT INTO sty_style (id, code, name, alias_name, style_class, brand, category_id, year, season, wave, gender, designer_id,
                       source, size_group_id, main_material_id, product_name, composition, tag_price, default_factory_id,
                       status, tech_pack_status) OVERRIDING SYSTEM VALUE VALUES
    (1, 'FS2611150101', '双排扣廓形短风衣', '小卡其', 'FS', 'DEMO', 3, 2026, 'AUTUMN', 'W1', 'WOMEN', 1,
     'OWN', 1, 1, '风衣', '100% 棉', 499, 3, 'ENABLED', 'NONE')
ON CONFLICT DO NOTHING;
INSERT INTO sty_style_color (id, style_id, color_id, seq) OVERRIDING SYSTEM VALUE VALUES (1, 1, 1, 1), (2, 1, 2, 2)
ON CONFLICT DO NOTHING;
INSERT INTO sty_style_sku (id, style_id, code, color_id, size_code, tag_price) OVERRIDING SYSTEM VALUE VALUES
    (11, 1, 'FS2611150101-KQ-S', 1, 'S', 499),
    (12, 1, 'FS2611150101-KQ-M', 1, 'M', 499),
    (13, 1, 'FS2611150101-KQ-L', 1, 'L', 499),
    (14, 1, 'FS2611150101-KQ-XL', 1, 'XL', 499),
    (20, 1, 'FS2611150101-BK-S', 2, 'S', 499),
    (21, 1, 'FS2611150101-BK-M', 2, 'M', 499),
    (22, 1, 'FS2611150101-BK-L', 2, 'L', 499),
    (23, 1, 'FS2611150101-BK-XL', 2, 'XL', 499)
ON CONFLICT DO NOTHING;

-- 自增调到最大 id 之后，页面上新建的接着往下排
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['bas_company', 'bas_color', 'bas_size_group', 'bas_size_group_item', 'bas_category',
                             'bas_material_category', 'sup_supplier', 'bas_warehouse', 'bas_material', 'bas_material_sku',
                             'sty_style', 'sty_style_color', 'sty_style_sku'] LOOP
        EXECUTE format('SELECT setval(pg_get_serial_sequence(%L, ''id''), GREATEST((SELECT max(id) FROM %I), 1))', t, t);
    END LOOP;
END $$;

COMMIT;
