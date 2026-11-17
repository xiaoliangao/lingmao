-- V24：物料类型的备料比默认 3%（甲方 11-17 答 Q18：采购时多买的比例一般 3%，PRD v0.17 §6.1.7）。
-- V11 预置的是 0。只改还是 0 的：已经有人在算料单的"备料比"里改过的不动。
UPDATE sys_dict_data
SET num_value = 3, update_time = now()
WHERE type_code = 'material_type' AND num_value = 0 AND NOT deleted;
