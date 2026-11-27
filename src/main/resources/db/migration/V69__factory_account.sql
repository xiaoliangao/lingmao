-- 工厂账号（甲方 11-27 答 Q02，RFC 019 §7）：加工厂、成衣厂自己登录，看本厂的生产单、报进度、登记实裁数。
-- 账号就是 sys_user，多一列 partner_id 指向供应商：不空 = 工厂账号。工厂账号不分角色、不看品类线和数据范围，
-- 权限由代码定（PermissionService 只给 scope:FACTORY:<id>，FactoryScopeInterceptor 只放标了 @FactoryAccess 的接口）。
-- 供应商被引用了删不掉（外键拦，报 1005"只能停用"）；停止合作的把工厂账号停用。

ALTER TABLE sys_user ADD COLUMN partner_id BIGINT REFERENCES sup_supplier (id);
COMMENT ON COLUMN sys_user.partner_id IS '工厂账号对应的加工厂、成衣厂（sup_supplier）；空 = 内部账号';
CREATE INDEX idx_sys_user_partner ON sys_user (partner_id) WHERE partner_id IS NOT NULL;
