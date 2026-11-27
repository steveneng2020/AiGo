-- 上下文起点：发往模型的历史只取这个时间戳之后的消息。
-- 用于「会话轮数被上游限制」时重新计数：界面与数据库里的历史完整保留，
-- 只是不再把这些旧消息发给模型，避免上游数到上限后拒绝服务。
-- 默认 0 表示不过滤，与升级前行为完全一致。
ALTER TABLE chat_sessions ADD COLUMN contextStartAt INTEGER NOT NULL DEFAULT 0;
