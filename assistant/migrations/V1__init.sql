-- V1：助手的对话、工具调用、用量（RFC 017 §3.4、§3.6，RFC 009 §3.5）。
-- 这些表在 assistant schema，归助手的库账号；app 不读也不写。user_id 是 app 里 sys_user 的 id，
-- 跨账号加不了外键，靠每次请求从 app 的 /api/auth/me 认人。
-- 早上待盯清单（ast_brief）、识别预填（ast_proposal）随那两档功能再加，这里不提前建。

-- 一次对话：谁开的、标题（第一句话的前 100 个字）
CREATE TABLE assistant.ast_conversation (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ast_conversation_user ON assistant.ast_conversation(user_id, updated_at DESC);

-- 每条提问和回答。回答出错时 content 空、error 写原因（给管理员看），这次调过的工具照样记在下表
CREATE TABLE assistant.ast_message (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES assistant.ast_conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    error VARCHAR(200),
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ast_message_conversation ON assistant.ast_message(conversation_id, id);

-- 一次回答里调了哪个工具、什么参数、app 回的 HTTP 状态、成没成、列表几条、用时
CREATE TABLE assistant.ast_tool_call (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES assistant.ast_message(id) ON DELETE CASCADE,
    tool VARCHAR(64) NOT NULL,
    args JSONB NOT NULL,
    http_status INT,
    ok BOOLEAN NOT NULL,
    rows INT,
    duration_ms INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ast_tool_call_message ON assistant.ast_tool_call(message_id);

-- 按人按天（东八区）的次数和 token：每人每天的次数限额、全系统每天的 token 限额都看它
CREATE TABLE assistant.ast_usage (
    user_id BIGINT NOT NULL,
    day DATE NOT NULL,
    requests INT NOT NULL DEFAULT 0,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day)
);
CREATE INDEX idx_ast_usage_day ON assistant.ast_usage(day);
