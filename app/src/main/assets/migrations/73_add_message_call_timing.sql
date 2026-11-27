-- 73: 消息表补「首 token」与「本次调用耗时」
--
-- 为什么要把这两个值搬进消息表：
--   气泡底部的统计行左右两半原本来自两张表——
--     · 左半（↑↓ token、缓存命中率）随消息落库，同步写 agent_messages，立刻可见；
--     · 右半（首 token、输出速度）只能等 workflow 在 finally 里 insert
--       llm_call_records，那个动作比消息落库晚（中间还有 actionQueue 的后续处理）。
--   两个写库时机不同，于是出现「左边已经显示、右边空着，过几秒才补上」的闪动，
--   同一轮里两次渲染还会算出不同的数（↑1.4M 变成 ↑2.1M）。
--
--   把首 token 与总耗时随消息一起写，左右同源同步，天然同时出现。
--   llm_call_records 仍是统计页与面板的数据源，两边各自独立、互为校验。
--
-- 列名必须与 Room 实体 `AgentMessageEntity` 的字段名逐字一致（camelCase），
-- 与其余 72 个迁移保持同一约定。写成 snake_case 会让 `SELECT *` 映射失败。
--
-- 允许 NULL：老消息没有这两列数据，留 NULL 让 UI 退回按 llm_call_records 取，
-- 不要用 0 冒充「耗时为零」，那会让速度算成无穷大。

ALTER TABLE agent_messages ADD COLUMN ttfbMillis INTEGER DEFAULT NULL;
ALTER TABLE agent_messages ADD COLUMN durationMillis INTEGER DEFAULT NULL;
