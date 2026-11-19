-- RFC 013 §2（甲方 11-18 答 Q13、Q30）：数据按品类线隔开，服装、鞋类、百货互相看不见。
-- 人归一条或几条线；款式、需求订单、算料单、采购单、外发加工单和成品采购单各属一条线，供应商可以做几条线。
-- 物料、颜色、尺码、仓库、出入库、库存、盘点不分线（仓库一个团队管）。
-- 单据上的 product_line 都不建索引：只有三个值，查询里只当过滤条件，列表照旧走原来的索引。
-- 列的默认值（单据 APPAREL，人和供应商三条都给）只给手写 SQL 的夹具和示例数据用；程序里新建都显式写。

-- 1. 字典：品类线。程序按 value 认，内置；名称能改，以后要加线在字典里加
INSERT INTO sys_dict_type (code, name, remark)
VALUES ('product_line', '品类线', '数据按它隔开：人、款式、单据、供应商各自属于哪几条（RFC 013 §2）');

INSERT INTO sys_dict_data (type_code, label, value, sort, builtin)
VALUES ('product_line', '服装', 'APPAREL',  1, true),
       ('product_line', '鞋类', 'FOOTWEAR', 2, true),
       ('product_line', '百货', 'GENERAL',  3, true);

-- 2. 款式大类定品类线：text_value 存 product_line 的 value。TS、FS 服装，XZ 鞋类；甲方建的别的大类先算服装，到字典页改
UPDATE sys_dict_type SET text_label = '品类线', text_dict = 'product_line'
WHERE code = 'style_class' AND deleted = false;

UPDATE sys_dict_data SET text_value = CASE value WHEN 'XZ' THEN 'FOOTWEAR' ELSE 'APPAREL' END
WHERE type_code = 'style_class' AND deleted = false;

-- 3. 人：能看哪几条线。已有的人三条都给，看到的和现在一样，管理员再按人收窄；超级管理员不看这一列，看全部
ALTER TABLE sys_user ADD COLUMN product_lines VARCHAR(16)[] NOT NULL DEFAULT '{APPAREL,FOOTWEAR,GENERAL}';
COMMENT ON COLUMN sys_user.product_lines IS '能看的品类线（字典 product_line 的 value）；超级管理员看全部';

-- 4. 款式：建款时按大类写，大类不能改所以它也不变
ALTER TABLE sty_style ADD COLUMN product_line VARCHAR(16) NOT NULL DEFAULT 'APPAREL';
UPDATE sty_style s SET product_line = COALESCE(
    (SELECT d.text_value FROM sys_dict_data d
     WHERE d.type_code = 'style_class' AND d.value = s.style_class AND d.deleted = false), 'APPAREL');
COMMENT ON COLUMN sty_style.product_line IS '品类线：建款时取大类的品类线（字典 style_class 的 text_value）';

-- 5. 需求订单：明细里的款都是这条线（一张单不混线）。已有的按第一行的款
ALTER TABLE ord_order ADD COLUMN product_line VARCHAR(16) NOT NULL DEFAULT 'APPAREL';
UPDATE ord_order o SET product_line = COALESCE(
    (SELECT s.product_line FROM ord_order_item i JOIN sty_style s ON s.id = i.style_id
     WHERE i.order_id = o.id ORDER BY i.seq, i.id LIMIT 1), 'APPAREL');
COMMENT ON COLUMN ord_order.product_line IS '品类线：明细里款式的品类线，一张单只能一条';

-- 6. 算料单：选的订单都是这条线。已有的按第一张订单
ALTER TABLE mrp_calc ADD COLUMN product_line VARCHAR(16) NOT NULL DEFAULT 'APPAREL';
UPDATE mrp_calc c SET product_line = COALESCE(
    (SELECT o.product_line FROM mrp_calc_order co JOIN ord_order o ON o.id = co.order_id
     WHERE co.calc_id = c.id ORDER BY o.id LIMIT 1), 'APPAREL');
COMMENT ON COLUMN mrp_calc.product_line IS '品类线：选的订单的品类线，一次只算一条线';

-- 7. 采购单：从算料单来的跟算料单；手工建的选一条。已有的：算料单的，没有就看关联的款，再没有算服装
ALTER TABLE pur_order ADD COLUMN product_line VARCHAR(16) NOT NULL DEFAULT 'APPAREL';
UPDATE pur_order p SET product_line = COALESCE(
    (SELECT c.product_line FROM mrp_calc c WHERE c.id = p.calc_id),
    (SELECT s.product_line FROM pur_order_style ps JOIN sty_style s ON s.id = ps.style_id
     WHERE ps.purchase_order_id = p.id ORDER BY s.id LIMIT 1),
    'APPAREL');
COMMENT ON COLUMN pur_order.product_line IS '品类线：从算料单生成的跟算料单，手工建的选；供应商要做这条线';

-- 8. 外发加工单、成品采购单：跟款式
ALTER TABLE prd_order ADD COLUMN product_line VARCHAR(16) NOT NULL DEFAULT 'APPAREL';
UPDATE prd_order p SET product_line = s.product_line FROM sty_style s WHERE s.id = p.style_id;
COMMENT ON COLUMN prd_order.product_line IS '品类线：款式的品类线；工厂要做这条线';

-- 9. 供应商：做哪几条线，至少一条。已有的三条都给
ALTER TABLE sup_supplier ADD COLUMN product_lines VARCHAR(16)[] NOT NULL DEFAULT '{APPAREL,FOOTWEAR,GENERAL}'
    CONSTRAINT ck_sup_supplier_lines CHECK (cardinality(product_lines) > 0);
COMMENT ON COLUMN sup_supplier.product_lines IS '做哪几条品类线（字典 product_line 的 value），至少一条；只有线有交集的人能看到';
