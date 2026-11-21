-- 角色上的"审核不受数据范围限制"（用户 11-21 定，PRD §3.2）：数据范围是"本人"的角色勾上后，
-- 有审核权限的变更单、物料出入库单、盘点单，待审的（和自己审过的）也看得到、审得了，不按本人筛。
-- 默认不勾，现有角色都不变；数据范围是"全部"的用不着，保存时存 false。一个人几个角色时，有一个启用的勾了就算（同数据范围取最宽）
ALTER TABLE sys_role ADD COLUMN audit_any_scope BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN sys_role.audit_any_scope IS '审核不受数据范围限制：本人范围的角色勾上后，单级审核的单据待审时不按本人筛';
