-- V1：系统域第一批表。Flyway 只执行一次，以后改结构加 V2、V3，不改这个文件。

CREATE TABLE sys_dict_type (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    remark      VARCHAR(255),
    status      SMALLINT     NOT NULL DEFAULT 1,          -- 1 启用 0 停用
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uk_sys_dict_type_code ON sys_dict_type (code);
COMMENT ON TABLE  sys_dict_type IS '字典类型';
COMMENT ON COLUMN sys_dict_type.code IS '类型编码，如 season、unit';

CREATE TABLE sys_dict_data (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type_code   VARCHAR(64)  NOT NULL,
    label       VARCHAR(64)  NOT NULL,                     -- 显示文本，如 春季
    value       VARCHAR(64)  NOT NULL,                     -- 存储值，如 1
    sort        INT          NOT NULL DEFAULT 0,
    status      SMALLINT     NOT NULL DEFAULT 1,
    remark      VARCHAR(255),
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_sys_dict_data_type ON sys_dict_data (type_code);
COMMENT ON TABLE sys_dict_data IS '字典数据';
