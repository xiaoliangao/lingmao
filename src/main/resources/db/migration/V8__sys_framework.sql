-- V8：框架补齐（RFC 001 §15、数据库设计 §3）。四件事互不相干，放一个文件是因为它们是同一批框架改动。

-- 1. 字典类型编码改成只在没删除的行里唯一（bug 001）。
--    V1 建的是全表唯一：逻辑删除的行还占着编码，删了的类型同名建不回来；Service 查重时自动带 deleted = false 查不到它，
--    插入时被索引拦住。和 V2 的字典数据、V3 的用户名、V5 的角色编码一个写法。
--    原来的全表唯一保证了没删的行之间也不重复，所以换成部分索引不会建失败。PostgreSQL 的 DDL 在事务里，删和建一起生效
DROP INDEX uk_sys_dict_type_code;
CREATE UNIQUE INDEX uk_sys_dict_type_code ON sys_dict_type (code) WHERE deleted = false;

-- 2. 流水号（架构 §4.5、RFC 001 §6.1）：单号、主数据编码、款号的流水都在这里计数，取代 Redis 的 INCR。
--    取号是一条 INSERT ... ON CONFLICT DO UPDATE ... RETURNING，在建单的事务里执行：建单失败号也回滚，单号连续；
--    行锁持有到事务提交，同一个 key 的取号排队。只往上加，作废的、删掉的草稿的号也不再发
CREATE TABLE sys_serial (
    seq_key     VARCHAR(32)  PRIMARY KEY,                -- 按天的：前缀 + 日期，如 CG20261105；不分天的：前缀，如 GYS；款号：大类 + 日期 + 设计师
    seq         INT          NOT NULL,                   -- 当前流水，已经发出去的最大号
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now()      -- 最后取号时间
);
COMMENT ON TABLE sys_serial IS '流水号';

-- 3. 角色的数据范围（PRD §3.2、RFC 001 §8）：ALL 全部 / SELF 本人。用户取所有启用角色里最宽的，有 ALL 就多给一个 data:all 权限。
--    默认 SELF：新建角色忘了设，最多是看不到别人的单（会来问），不会看到不该看的。超级管理员是 ALL
ALTER TABLE sys_role ADD COLUMN data_scope VARCHAR(8) NOT NULL DEFAULT 'SELF';
COMMENT ON COLUMN sys_role.data_scope IS '数据范围：ALL 全部 / SELF 本人';
UPDATE sys_role SET data_scope = 'ALL' WHERE code = 'admin';

-- 4. 操作日志记下这次请求的错误编号（RFC 001 §5.3，TraceIdFilter 生成的 8 位编号）：从操作日志能 grep 到应用日志里这次请求的每一行
ALTER TABLE sys_oper_log ADD COLUMN trace_id VARCHAR(32);
