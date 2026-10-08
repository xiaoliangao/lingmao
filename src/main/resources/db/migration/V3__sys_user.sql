-- V3：系统用户表。登录要用，用户管理（03）也在这张表上做。

CREATE TABLE sys_user (
                          id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                          username    VARCHAR(64)  NOT NULL,                   -- 登录名，如 admin
                          password    VARCHAR(100) NOT NULL,                   -- BCrypt 哈希，不是明文；哈希固定 60 个字符，留点余量
                          nickname    VARCHAR(64)  NOT NULL,                   -- 显示名，如 管理员
                          phone       VARCHAR(20),
                          status      SMALLINT     NOT NULL DEFAULT 1,         -- 1 启用 0 停用
                          create_by   BIGINT,
                          create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
                          update_by   BIGINT,
                          update_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
                          deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
-- 登录名在未删除的用户里唯一（部分索引，和字典数据同一个道理）
CREATE UNIQUE INDEX uk_sys_user_username ON sys_user (username) WHERE deleted = false;
COMMENT ON TABLE sys_user IS '系统用户';