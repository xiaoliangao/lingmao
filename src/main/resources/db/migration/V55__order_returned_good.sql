-- 订单行的"已回货"改成只算良品（回货 − 次品，甲方 11-19"只计算良品"）：次品退回去返修，修好再登记一次回货，
-- 按回货数加会把同一件算两遍。登记回货、作废回货单以后只加减良品；这里把已有的按生产单明细重算一遍。
-- 生产单明细上的 returned_qty 仍是回货数（含次品），defect_qty 是其中次品，不动
UPDATE ord_order_item oi
SET returned_qty = g.good
FROM (SELECT order_item_id, SUM(returned_qty - defect_qty) AS good
      FROM prd_order_item
      GROUP BY order_item_id) g
WHERE g.order_item_id = oi.id
  AND oi.returned_qty <> g.good;

COMMENT ON COLUMN ord_order_item.returned_qty IS '已回货，只算良品（回货 − 次品），回货单回写';
