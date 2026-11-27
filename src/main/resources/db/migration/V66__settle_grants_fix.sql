-- V66：收回 V65 多给的两处对账授权（RFC 019 §5.1）。
-- 1. 跟单不看对账单：对账单上的金额跟着对账权限走（FieldPermissions.SETTLE），里面有物料的采购单价、金额，
--    跟单本来看不到物料采购价（没有 techpack:cost:query）。跟单还留着扣款登记（看、登记自己的延期扣款）。
-- 2. 采购不给对账：对账单、扣款按制单人分范围，采购的数据范围是"本人"又不能生成、登记，给了也只看到空表。
-- 别的角色不动；要给谁，管理员在角色里勾
DELETE FROM sys_role_menu rm
USING sys_role r, sys_menu m
WHERE rm.role_id = r.id
  AND rm.menu_id = m.id
  AND r.deleted = false
  AND m.deleted = false
  AND ((r.code = 'merchandiser' AND m.perms = 'settle:statement:list')
    OR (r.code = 'buyer' AND m.perms IN ('settle:statement:list', 'settle:deduction:list')));

-- "对账结算"目录下一个页面都没有了的，目录的授权也收掉（采购）
DELETE FROM sys_role_menu rm
USING sys_role r, sys_menu dir
WHERE rm.role_id = r.id
  AND rm.menu_id = dir.id
  AND r.code = 'buyer'
  AND dir.parent_id = 0
  AND dir.path = '/settle'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x JOIN sys_menu c ON c.id = x.menu_id
                  WHERE x.role_id = r.id AND c.parent_id = dir.id AND c.deleted = false);
