-- 需求订单行的已回货再核一遍（V55 的补充，agy 审查 T-1637ea #4）。
-- 口径（用户 11-19 定"只计算良品"）：订单行的已回货 = 对应生产单明细的良品（回货 − 次品）之和，作废的生产单不算。
-- 订单的已回货只有生产单登记、作废回货单时会写（OrderService.addReturned），订单新建时是 0，
-- 所以没有生产单明细的行应该是 0：V55 只更新了有生产单明细的行，这里连没有的一起核，对不上的才改。
-- 只改数，不动列；11-22 查过开发库和试用环境都还没有回货单，没有靠次品凑够 97% 已经完成的生产单，不用重算状态。
UPDATE ord_order_item oi
SET returned_qty = g.good
FROM (SELECT o.id,
             COALESCE(SUM(pi.returned_qty - pi.defect_qty) FILTER (WHERE p.status <> 'VOIDED'), 0) AS good
      FROM ord_order_item o
      LEFT JOIN prd_order_item pi ON pi.order_item_id = o.id
      LEFT JOIN prd_order p ON p.id = pi.prd_order_id
      GROUP BY o.id) g
WHERE g.id = oi.id
  AND oi.returned_qty <> g.good;
