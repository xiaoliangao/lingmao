-- 采购超收冲抵在途（RFC 014）：同一组（同主体、供应商、品类线、物料 SKU、采购单位）的采购行，一张多收的冲给没到齐的。
-- 到货 = 净入库（已入库 − 已退货）+ 冲入 − 冲出；未入库 = max(0, 采购数 − 到货)。超收上限还是只看净入库。

ALTER TABLE pur_order_item
    ADD COLUMN offset_in_qty  NUMERIC(14, 3) NOT NULL DEFAULT 0,
    ADD COLUMN offset_out_qty NUMERIC(14, 3) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_pur_item_offset CHECK (offset_in_qty >= 0 AND offset_out_qty >= 0);

COMMENT ON COLUMN pur_order_item.offset_in_qty IS '冲入：同组别的行多收、冲给这一行的，采购单位。是 pur_over_offset 里 to_item_id 指着它的合计';
COMMENT ON COLUMN pur_order_item.offset_out_qty IS '冲出：这一行多收、冲给同组别的行的，采购单位。是 pur_over_offset 里 from_item_id 指着它的合计';

-- 一笔冲抵；退回时改小，退完删掉。只在采购单审核以后才会有，所以不挡改草稿、删草稿
CREATE TABLE pur_over_offset (
    id           BIGSERIAL      PRIMARY KEY,
    from_item_id BIGINT         NOT NULL REFERENCES pur_order_item (id),
    to_item_id   BIGINT         NOT NULL REFERENCES pur_order_item (id),
    qty          NUMERIC(14, 3) NOT NULL CHECK (qty > 0),
    create_time  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_pur_offset_self CHECK (from_item_id <> to_item_id)
);
COMMENT ON TABLE pur_over_offset IS '采购超收冲抵（RFC 014）：from 那一行多收的冲给 to 那一行；create_time 是多收那一刻，退回再冲时只冲给那以前审核的单';
CREATE INDEX idx_pur_offset_from ON pur_over_offset (from_item_id);
CREATE INDEX idx_pur_offset_to ON pur_over_offset (to_item_id);

-- 在途、待到料用的部分索引改成按到货算（查询里的条件要和这里一字不差）
DROP INDEX idx_pur_item_open;
CREATE INDEX idx_pur_item_open ON pur_order_item (material_sku_id)
    WHERE received_qty - returned_qty + offset_in_qty - offset_out_qty < qty;
