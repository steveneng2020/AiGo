-- 72: 工具执行耗时埋点
--
-- 为什么需要独立表：`llm_call_records` 是「一次 API 调用一条」，而工具执行
-- 发生在两次调用之间、与任何单个调用都不对应。把耗时塞进调用记录只能靠
-- 「相邻调用空档」反推，那个推算会把网络往返、用户授权等待一并算成工具耗时。
--
-- 列名必须与 Room 实体 `ToolExecutionRecordEntity` 的字段名**逐字一致**：
-- 本仓库其余 71 个迁移全部使用 camelCase（如 reasoningTokens），Room 也按
-- camelCase 生成列名。写成 snake_case 会让 `WHERE sessionId = ?` 找不到列，
-- 插入与查询双双失败。

CREATE TABLE IF NOT EXISTS tool_execution_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    sessionId TEXT,
    messageId TEXT,
    toolCallId TEXT,
    toolName TEXT,
    startedAt INTEGER NOT NULL,
    durationMillis INTEGER NOT NULL,
    status TEXT NOT NULL,
    errorMessage TEXT
);

CREATE INDEX IF NOT EXISTS index_tool_execution_records_sessionId
    ON tool_execution_records(sessionId);

CREATE INDEX IF NOT EXISTS index_tool_execution_records_startedAt
    ON tool_execution_records(startedAt);
