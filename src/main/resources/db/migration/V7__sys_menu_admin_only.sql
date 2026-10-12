-- V7：管理后台只给超级管理员。菜单加一个标记 admin_only：
--   标了的菜单（系统管理目录和它下面的一切）不能分配给别的角色；
--   就算 sys_role_menu 里有记录，非超级管理员也拿不到它的权限和页面（查询时过滤，见 SysMenuMapper）

ALTER TABLE sys_menu ADD COLUMN admin_only BOOLEAN NOT NULL DEFAULT FALSE;   -- true：管理后台的菜单，只有超级管理员能用

-- 系统管理目录和它的全部下级，递归找出来
WITH RECURSIVE admin_tree AS (
    SELECT id FROM sys_menu WHERE parent_id = 0 AND path = '/system' AND deleted = false
    UNION ALL
    SELECT m.id FROM sys_menu m JOIN admin_tree t ON m.parent_id = t.id WHERE m.deleted = false
)
UPDATE sys_menu SET admin_only = TRUE WHERE id IN (SELECT id FROM admin_tree);

-- 以前分给其他角色的管理后台菜单，收回
DELETE FROM sys_role_menu WHERE menu_id IN (SELECT id FROM sys_menu WHERE admin_only = TRUE);
