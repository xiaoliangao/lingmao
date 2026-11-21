-- RFC 016 §2：翻单、补单记住是从哪张订单复制的。source = 'REPEAT' 时必须有 source_order_id，反过来也一样。
-- 原单被引用后删不掉（外键拦）；原单只有草稿能删，而草稿不能翻单，正常走不到。
-- 一期没有写过 REPEAT 的代码，已有的订单都是 MANUAL / IMPORT，约束加得上。
ALTER TABLE ord_order ADD COLUMN source_order_id BIGINT REFERENCES ord_order (id);
COMMENT ON COLUMN ord_order.source_order_id IS '翻单、补单的原单（source = REPEAT 时有）';
CREATE INDEX idx_ord_order_source ON ord_order (source_order_id);
ALTER TABLE ord_order ADD CONSTRAINT ck_ord_order_source
    CHECK ((source = 'REPEAT') = (source_order_id IS NOT NULL));
