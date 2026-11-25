-- V58：甲方 11-25 给的款号、物料编码规则要用到的两处（RFC 018 §8.3）。

-- 1. 物料分类编码：二级是 03 这种数字，不同一级下会重复。
--    原来全部分类里不重复，改成同一个上级下不重复（一级的 parent_id 是 NULL，按 0 算，一级之间照样不重复）。
--    字母或数字、一级选填、二级必填、能改，由 Service 校验
DROP INDEX uk_bas_mat_cat_code;
CREATE UNIQUE INDEX uk_bas_mat_cat_code ON bas_material_category (COALESCE(parent_id, 0), code) WHERE code IS NOT NULL;
COMMENT ON COLUMN bas_material_category.code IS
    '分类编码：2 位大写字母或数字，二级必填、一级选填，同一个上级下不重复；编码规则里的一级、二级分类编码取它。改了只影响以后新编的物料编码';

-- 2. 品牌编码：品牌的值是导入款式时的中文名，值建好又不能改，款号里的"品牌编码"取不出字母。
--    给品牌加一列"编码"（存在 text_value，自由填），编号时先取它；没填的、值本身是字母数字的用值
UPDATE sys_dict_type SET text_label = '编码', update_time = now()
WHERE code = 'brand' AND text_label IS NULL AND deleted = false;
