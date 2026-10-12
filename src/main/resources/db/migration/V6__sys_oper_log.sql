-- V6：操作日志。只追加、不修改、不删除，所以没有 update_* 和 deleted

CREATE TABLE sys_oper_log (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT,                         -- 操作人；登录这类还没认出人的操作为空
    username       VARCHAR(64),                    -- 冗余一份：用户被删了，日志里也看得出是谁
    module         VARCHAR(64)  NOT NULL,          -- 模块，如 用户管理
    action         VARCHAR(64)  NOT NULL,          -- 动作，如 新增
    method         VARCHAR(200) NOT NULL,          -- 哪个 Java 方法，如 UserController.create
    request_method VARCHAR(10),                    -- GET / POST / PUT / DELETE
    request_uri    VARCHAR(255),
    params         TEXT,                           -- 入参 JSON，密码已打码，超长截断
    success        BOOLEAN      NOT NULL,
    error_msg      VARCHAR(500),                   -- 失败原因
    cost_ms        INT          NOT NULL,          -- 耗时，毫秒
    ip             VARCHAR(64),
    create_time    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_sys_oper_log_time ON sys_oper_log (create_time DESC);
COMMENT ON TABLE sys_oper_log IS '操作日志';

-- 系统管理下加一个"操作日志"页面
INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort)
SELECT id, '操作日志', 2, 'log', 'system/log/index', 'system:log:list', 'Document', 5
FROM sys_menu WHERE type = 1 AND path = '/system' AND deleted = false;
