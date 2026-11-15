-- V11：字典的新列和一期全部预置（RFC 003 §4、数据库设计 §3.1、§3.2），用户的五个新列（RFC 003 §3.2、数据库设计 §3.3）。
-- sys_ 表沿用逻辑删除（deleted），部分唯一索引带 WHERE deleted = false。

-- 1. 字典的新列
--    数值、文字：生产节点的默认天数、物料类型的备料比放数值，尺寸部位的默认测量方法放文字，不为它们另建表（PRD §6.1.7 规则 1）
ALTER TABLE sys_dict_type ADD COLUMN num_label VARCHAR(32);              -- 这个字典的数值叫什么，如"默认天数"；空 = 不用数值
ALTER TABLE sys_dict_data ADD COLUMN num_value NUMERIC(14,4);            -- 数值，含义看类型的 num_label
ALTER TABLE sys_dict_data ADD COLUMN builtin BOOLEAN NOT NULL DEFAULT false; -- 内置项：代码按 value 认它，不能删、不能停用，名称能改
ALTER TABLE sys_dict_data ADD COLUMN text_value VARCHAR(500);            -- 长一点的文字，如尺寸部位的默认测量方法（设置 › 工艺标准里填）
COMMENT ON COLUMN sys_dict_type.num_label IS '数值列的名字，如 默认天数、备料比 %；空 = 这个字典不用数值';
COMMENT ON COLUMN sys_dict_data.num_value IS '数值：生产节点的默认天数、物料类型的备料比 %';
COMMENT ON COLUMN sys_dict_data.builtin IS '内置项：代码按 value 认它，不能删、不能停用，名称能改';
COMMENT ON COLUMN sys_dict_data.text_value IS '文字：尺寸部位的默认测量方法';

-- 2. 预置的字典类型（数据库设计 §3.2，二期的打样节点、风险类型这次不建）。
--    ON CONFLICT DO NOTHING：库里已经有同编码的类型（开发库、试用环境里手建过）就不动它。
--    品牌 brand、系列 series 只建类型不建项：品牌是甲方的名字，不进公开仓库；导款式时没有的品牌自动建（RFC 005 §8）
INSERT INTO sys_dict_type (code, name, remark)
VALUES ('style_class',       '款式大类',     'value 就是款号开头的 2 位字母'),
       ('season',            '季节',         NULL),
       ('wave',              '波段',         NULL),
       ('style_tag',         '风格',         NULL),
       ('brand',             '品牌',         '导入款式时没有的品牌自动建；手建款式前先在这里加'),
       ('series',            '系列',         NULL),
       ('gender',            '性别',         NULL),
       ('style_source',      '款式来源',     NULL),
       ('exec_standard',     '执行标准',     '款式吊牌信息'),
       ('safety_category',   '安全类别',     '款式吊牌信息'),
       ('color_family',      '色系',         NULL),
       ('unit',              '单位',         NULL),
       ('currency',          '币种',         NULL),
       ('tax_rate',          '税率',         'value 是税率的数字，如 13'),
       ('settle_method',     '结算方式',     NULL),
       ('invoice_type',      '发票类型',     NULL),
       ('material_type',     '物料类型',     '数值是备料比：物料上没设备料比时用它'),
       ('supplier_category', '供应商分类',   NULL),
       ('order_type',        '订单类型',     NULL),
       ('process_mode',      '加工方式',     '包工包料排出来是成品采购单，其余是外发加工单'),
       ('prod_node',         '生产节点',     '数值是默认天数'),
       ('issue_type',        '异常类型',     NULL),
       ('bom_part',          'BOM 部位',     NULL),
       ('process_category',  '工序类别',     NULL),
       ('size_point',        '尺寸部位',     '文字是默认测量方法，在 设置 › 工艺标准 里填'),
       ('packing_mode',      '包装方式',     NULL),
       ('cert_type',         '证照类型',     NULL),
       ('stock_in_reason',   '其他入库原因', NULL),
       ('stock_out_reason',  '其他出库原因', NULL)
ON CONFLICT (code) WHERE deleted = false DO NOTHING;

UPDATE sys_dict_type SET num_label = v.num_label
FROM (VALUES ('material_type', '备料比 %'),
             ('prod_node',     '默认天数')) AS v(code, num_label)
WHERE sys_dict_type.code = v.code AND sys_dict_type.deleted = false AND sys_dict_type.num_label IS NULL;

-- 3. 预置的字典项（数据库设计 §3.2 的"预置项"一列）。value 用英文大写，建好不能改（RFC 003 §4.2）；label 甲方能改。
--    业务表存 value：label 改了不影响历史数据。物料类型的备料比默认 0（PRD §6.1.7），生产节点的天数照原型
INSERT INTO sys_dict_data (type_code, label, value, sort, num_value)
VALUES
    -- 款式
    ('style_class', '服饰', 'FS', 1, NULL),
    ('style_class', '鞋子', 'XZ', 2, NULL),
    ('season', '春', 'SPRING', 1, NULL),
    ('season', '夏', 'SUMMER', 2, NULL),
    ('season', '秋', 'AUTUMN', 3, NULL),
    ('season', '冬', 'WINTER', 4, NULL),
    ('wave', '第 1 波', 'W1', 1, NULL),
    ('wave', '第 2 波', 'W2', 2, NULL),
    ('wave', '第 3 波', 'W3', 3, NULL),
    ('wave', '第 4 波', 'W4', 4, NULL),
    ('wave', '第 5 波', 'W5', 5, NULL),
    ('wave', '第 6 波', 'W6', 6, NULL),
    ('style_tag', '通勤', 'COMMUTE', 1, NULL),
    ('style_tag', '休闲', 'CASUAL', 2, NULL),
    ('style_tag', '运动', 'SPORT', 3, NULL),
    ('gender', '女', 'WOMEN', 1, NULL),
    ('gender', '男', 'MEN', 2, NULL),
    ('gender', '童', 'KIDS', 3, NULL),
    ('gender', '中性', 'UNISEX', 4, NULL),
    ('style_source', '自主开发', 'OWN', 1, NULL),
    ('style_source', '选款', 'SELECTED', 2, NULL),
    ('style_source', '改款', 'MODIFIED', 3, NULL),
    ('style_source', '客户来样', 'CUSTOMER_SAMPLE', 4, NULL),          -- 代工保留：TRADE 关时下拉里滤掉、后端拒收
    ('exec_standard', 'GB/T 2664-2017 男西服、大衣', 'GB/T 2664-2017', 1, NULL),
    ('exec_standard', 'GB/T 2665-2017 女西服、大衣', 'GB/T 2665-2017', 2, NULL),
    ('exec_standard', 'FZ/T 81004-2022 连衣裙、裙套', 'FZ/T 81004-2022', 3, NULL),
    ('exec_standard', 'FZ/T 73020-2019 针织休闲服装', 'FZ/T 73020-2019', 4, NULL),
    ('exec_standard', 'GB/T 22849-2014 针织 T 恤衫', 'GB/T 22849-2014', 5, NULL),
    ('safety_category', 'A 类', 'A', 1, NULL),
    ('safety_category', 'B 类', 'B', 2, NULL),
    ('safety_category', 'C 类', 'C', 3, NULL),
    ('color_family', '黑白灰', 'NEUTRAL', 1, NULL),
    ('color_family', '红', 'RED', 2, NULL),
    ('color_family', '蓝', 'BLUE', 3, NULL),
    ('color_family', '绿', 'GREEN', 4, NULL),
    ('color_family', '黄', 'YELLOW', 5, NULL),
    ('color_family', '棕', 'BROWN', 6, NULL),
    ('color_family', '紫', 'PURPLE', 7, NULL),
    -- 物料、单据通用。根、颗、厘米是甲方的 BOM 表在用的（数据库设计附录 B.5）
    ('unit', '米', 'METER', 1, NULL),
    ('unit', '码', 'YARD', 2, NULL),
    ('unit', '公斤', 'KG', 3, NULL),
    ('unit', '个', 'EACH', 4, NULL),
    ('unit', '条', 'STRIP', 5, NULL),
    ('unit', '套', 'SET', 6, NULL),
    ('unit', '张', 'SHEET', 7, NULL),
    ('unit', '卷', 'ROLL', 8, NULL),
    ('unit', '件', 'PIECE', 9, NULL),                                   -- 加工价的单位（RFC 006 §3）
    ('unit', '根', 'STICK', 10, NULL),
    ('unit', '颗', 'BEAD', 11, NULL),
    ('unit', '厘米', 'CM', 12, NULL),
    ('currency', '人民币', 'CNY', 1, NULL),
    ('currency', '美元', 'USD', 2, NULL),
    ('currency', '欧元', 'EUR', 3, NULL),
    ('currency', '港币', 'HKD', 4, NULL),
    ('tax_rate', '0%', '0', 1, NULL),
    ('tax_rate', '1%', '1', 2, NULL),
    ('tax_rate', '3%', '3', 3, NULL),
    ('tax_rate', '6%', '6', 4, NULL),
    ('tax_rate', '9%', '9', 5, NULL),
    ('tax_rate', '13%', '13', 6, NULL),
    ('settle_method', '月结', 'MONTHLY', 1, NULL),
    ('settle_method', '货到付款', 'COD', 2, NULL),
    ('settle_method', '预付', 'PREPAID', 3, NULL),
    ('settle_method', '银行转账', 'TRANSFER', 4, NULL),
    ('settle_method', '承兑', 'ACCEPTANCE', 5, NULL),
    ('invoice_type', '专票', 'SPECIAL', 1, NULL),
    ('invoice_type', '普票', 'GENERAL', 2, NULL),
    ('invoice_type', '不开票', 'NONE', 3, NULL),
    -- 配件、工艺、鞋底是 PRD Q40 的默认做法
    ('material_type', '面料', 'FABRIC', 1, 0),
    ('material_type', '里料', 'LINING', 2, 0),
    ('material_type', '辅料', 'TRIM', 3, 0),
    ('material_type', '包材', 'PACKING', 4, 0),
    ('material_type', '标签吊牌', 'LABEL', 5, 0),
    ('material_type', '配件', 'ACCESSORY', 6, 0),
    ('material_type', '工艺', 'CRAFT', 7, 0),
    ('material_type', '鞋底', 'SOLE', 8, 0),
    -- 供应商
    ('supplier_category', '面料商', 'FABRIC', 1, NULL),
    ('supplier_category', '辅料商', 'TRIM', 2, NULL),
    ('supplier_category', '成衣厂', 'GARMENT', 3, NULL),
    ('supplier_category', '加工厂', 'PROCESSOR', 4, NULL),
    ('supplier_category', '外协（印绣洗）', 'OUTSOURCE', 5, NULL),
    ('supplier_category', '包材商', 'PACKING', 6, NULL),
    ('supplier_category', '其他', 'OTHER', 7, NULL),
    ('cert_type', '营业执照', 'LICENSE', 1, NULL),
    ('cert_type', '验厂报告', 'FACTORY_AUDIT', 2, NULL),
    ('cert_type', '环保证明', 'ENVIRONMENTAL', 3, NULL),
    ('cert_type', '其他', 'OTHER', 4, NULL),
    -- 后面几批才用，这次也插：甲方能在试用环境里先看、先改（RFC 003 §4.3）
    ('order_type', '自营备货', 'STOCK', 1, NULL),
    ('order_type', '补单', 'REPLENISH', 2, NULL),
    ('order_type', '翻单', 'REPEAT', 3, NULL),
    ('order_type', '客户订单', 'CUSTOMER', 4, NULL),                    -- 代工保留：TRADE 关时下拉里滤掉
    ('process_mode', '包工包料', 'FULL', 1, NULL),
    ('process_mode', '清加工', 'CMT', 2, NULL),
    ('process_mode', '半包', 'HALF', 3, NULL),
    ('prod_node', '到料', 'MATERIAL', 1, 3),
    ('prod_node', '裁剪', 'CUT', 2, 4),
    ('prod_node', '车缝', 'SEW', 3, 10),
    ('prod_node', '后整', 'FINISH', 4, 3),
    ('prod_node', '包装', 'PACK', 5, 2),
    ('prod_node', '入仓', 'WAREHOUSE', 6, 1),
    ('issue_type', '缺料', 'SHORTAGE', 1, NULL),
    ('issue_type', '面料质量', 'FABRIC_QUALITY', 2, NULL),
    ('issue_type', '做工质量', 'WORKMANSHIP', 3, NULL),
    ('issue_type', '延期', 'DELAY', 4, NULL),
    ('issue_type', '工艺问题', 'CRAFT', 5, NULL),
    ('issue_type', '其他', 'OTHER', 6, NULL),
    ('bom_part', '大身', 'BODY', 1, NULL),
    ('bom_part', '里布', 'LINING', 2, NULL),
    ('bom_part', '袋布', 'POCKETING', 3, NULL),
    ('bom_part', '领', 'COLLAR', 4, NULL),
    ('bom_part', '袖', 'SLEEVE', 5, NULL),
    ('bom_part', '拉链', 'ZIPPER', 6, NULL),
    ('bom_part', '纽扣', 'BUTTON', 7, NULL),
    ('bom_part', '织带', 'WEBBING', 8, NULL),
    ('bom_part', '主唛', 'MAIN_LABEL', 9, NULL),
    ('bom_part', '洗唛', 'CARE_LABEL', 10, NULL),
    ('bom_part', '吊牌', 'HANGTAG', 11, NULL),
    ('bom_part', '包装袋', 'POLYBAG', 12, NULL),
    ('bom_part', '缝纫线', 'THREAD', 13, NULL),
    ('process_category', '裁剪', 'CUTTING', 1, NULL),
    ('process_category', '车缝', 'SEWING', 2, NULL),
    ('process_category', '整烫', 'IRONING', 3, NULL),
    ('process_category', '后道', 'FINISHING', 4, NULL),
    ('process_category', '包装', 'PACKING', 5, NULL),
    ('size_point', '后中长', 'BACK_LENGTH', 1, NULL),
    ('size_point', '肩袖长', 'SHOULDER_SLEEVE', 2, NULL),
    ('size_point', '胸围', 'CHEST', 3, NULL),
    ('size_point', '摆围', 'HEM', 4, NULL),
    ('size_point', '领圈', 'NECK_OPENING', 5, NULL),
    ('size_point', '领围', 'NECK', 6, NULL),
    ('size_point', '袖肥', 'BICEP', 7, NULL),
    ('size_point', '袖口', 'CUFF', 8, NULL),
    ('packing_mode', '平铺', 'FLAT', 1, NULL),
    ('packing_mode', '挂装', 'HANGING', 2, NULL),
    ('packing_mode', '折叠', 'FOLDED', 3, NULL),
    ('stock_in_reason', '期初', 'OPENING', 1, NULL),
    ('stock_in_reason', '样品', 'SAMPLE', 2, NULL),
    ('stock_in_reason', '其他', 'OTHER', 3, NULL),
    ('stock_out_reason', '报废', 'SCRAP', 1, NULL),
    ('stock_out_reason', '卖余料', 'SELL_SURPLUS', 2, NULL),
    ('stock_out_reason', '其他', 'OTHER', 3, NULL)
ON CONFLICT (type_code, value) WHERE deleted = false DO NOTHING;

-- 4. 内置项（PRD §6.1.7 规则 2、数据库设计 §3.2"内置项"一列）：代码按 value 认它们。
--    单独一条 UPDATE，不写在上面的 INSERT 里：同一个 value 已经有了（上面跳过了），也照样标上
UPDATE sys_dict_data SET builtin = true
WHERE deleted = false
  AND (type_code, value) IN (('style_source',      'CUSTOMER_SAMPLE'),  -- 客户来样：TRADE 关时滤掉
                             ('material_type',     'FABRIC'),           -- 面料、里料：核价按它分"面料"和"辅料&耗材"
                             ('material_type',     'LINING'),
                             ('supplier_category', 'GARMENT'),          -- 成衣厂、加工厂：自动建工厂仓、能排单
                             ('supplier_category', 'PROCESSOR'),
                             ('order_type',        'CUSTOMER'),         -- 客户订单：TRADE 关时滤掉
                             ('process_mode',      'FULL'),             -- 包工包料：排出来是成品采购单
                             ('prod_node',         'CUT'),              -- 裁剪：要料日期取它的计划开始
                             ('cert_type',         'LICENSE'),          -- 营业执照：申请准入时必须有
                             ('stock_in_reason',   'OPENING'),          -- 期初
                             ('currency',          'CNY'),              -- 人民币
                             ('unit',              'PIECE'),            -- 件：加工价的单位固定是它（RFC 006 §3）
                             ('style_source',      'OWN'));             -- 自主开发：导入款式时来源空就填它（RFC 005 §8）

-- 5. 用户的五个新列（RFC 003 §3.2）。system 不能调 basic，默认主体存在由外键保证；停用的主体照样能留在用户上
ALTER TABLE sys_user
    ADD COLUMN default_company_id BIGINT REFERENCES bas_company (id),
    ADD COLUMN dept               VARCHAR(64),
    ADD COLUMN post               VARCHAR(64),
    ADD COLUMN designer_no        VARCHAR(2),
    ADD COLUMN designer_no_locked BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN sys_user.default_company_id IS '默认主体 bas_company.id：新建单据带出；空或停用时取唯一启用的主体';
COMMENT ON COLUMN sys_user.dept IS '部门，自由填';
COMMENT ON COLUMN sys_user.post IS '岗位，自由填';
COMMENT ON COLUMN sys_user.designer_no IS '设计师编号，两位数字 01-99，进款号（架构 §4.5）';
COMMENT ON COLUMN sys_user.designer_no_locked IS '第一次用它自动编款号时置 true，之后不能改、不能清空';
-- 设计师编号全表唯一、不带 deleted 条件：离职（删除）的人的编号也不再给别人，老款号上的编号永远只对应一个人
CREATE UNIQUE INDEX uk_sys_user_designer_no ON sys_user (designer_no);
CREATE INDEX idx_sys_user_company ON sys_user (default_company_id);
