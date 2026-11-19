-- V40：加工费不再按加工方式从价格档案带默认值，加一个备注写清是什么价（甲方 11-18 答 Q50："清加工价和半包价不用区分，有一个备注的地方写对应的价格即可"）
ALTER TABLE tp_tech_pack ADD COLUMN labor_note VARCHAR(255);
COMMENT ON COLUMN tp_tech_pack.labor_note IS '加工费备注，如"清加工 38，半包 45"；和加工费一样要看成本权限';
COMMENT ON COLUMN tp_tech_pack.labor_price IS '加工费，元/件，按外发算时用；手填或从 BOM 表导入，空 = 0';
