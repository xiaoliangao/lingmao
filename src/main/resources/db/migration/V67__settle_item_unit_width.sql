-- V67：对账明细的单位放宽到和来源一样（agy 复查 T-adeaba #4）。
-- V65 建成 VARCHAR(16)，单位来自入库明细的采购单位、物料的库存单位，都是 VARCHAR(64)（字典 unit 的 value），
-- 长的单位编码生成对账单时会超长报错。只放宽长度，已有的数据不变
ALTER TABLE stl_statement_item ALTER COLUMN unit TYPE VARCHAR(64);
