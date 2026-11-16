-- 工艺标准不再单独成页（用户 11-16 定）：改成资料包页签上拉出的抽屉（web/src/views/techpack/standard/CraftStandardDrawer.vue）。
-- 菜单留着、藏起来，和"资料包""价格档案"一样：/api/auth/me 只给 visible 的目录和页面，侧边栏、路由里就没有它了；
-- 下面的"修改"按钮权限（techpack:standard:edit）和各角色的授权照旧，角色页里照样能勾
UPDATE sys_menu
SET visible = false, update_time = now()
WHERE path = '/craft-standard' AND type = 2 AND deleted = false;
