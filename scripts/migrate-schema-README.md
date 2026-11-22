# 跨 schema 版本迁移脚本

## 解决什么问题

AiGo 是 Nox 的 fork，但 **Room 数据库 schema 落后上游**：

| | AiGo | Nox |
|---|---|---|
| versionName | 1.0.0 | 1.0.7 |
| Room `SCHEMA_VERSION` | **64** | **68** |

把 Nox 的 `nox_agent_db` **直接覆盖**到 AiGo，会闪退：

```
java.lang.IllegalStateException: A migration from 68 to 64 was required but not found.
```

**原因**：Room 只支持版本升级（64→68），不支持降级（68→64）。

## 脚本做什么

以「目标端自己重建的空库」为骨架，把源端数据**逐行搬进去**，只迁共有列。

```
源库（schema 68）
  ↓  只取两边都有的列
目标空库（schema 64，由 App 自己重建）
  ↓
输出（schema 64，含源端数据）
```

## 用法

### 第 1 步：让目标 App 重建空库

如果目标库已损坏或是错的 schema 版本，先删掉再启动 App：

```sh
# 宿主 Root 通道
am force-stop com.aigo
rm -f /data/user/0/com.aigo/databases/nox_agent_db*
am start -n com.aigo/com.nox.MainActivity
# 等 15 秒，App 会建出新库（schema 64）
```

### 第 2 步：导出两边副本

```sh
# 宿主 Root 通道，先 checkpoint 再拷，避免丢 WAL 里的事务
cp /data/user/0/com.nox/databases/nox_agent_db        /data/user/0/com.nox/files/nox/src.db
cp /data/user/0/com.nox/databases/nox_agent_db-wal    /data/user/0/com.nox/files/nox/src.db-wal
cp /data/user/0/com.aigo/databases/nox_agent_db       /data/user/0/com.nox/files/nox/tmpl.db
cp /data/user/0/com.aigo/databases/nox_agent_db-wal   /data/user/0/com.nox/files/nox/tmpl.db-wal
chmod 644 /data/user/0/com.nox/files/nox/{src,tmpl}.db*
```

模板库要 `VACUUM INTO` 成单文件（否则表结构还在 WAL 里）：

```sh
python3 -c "import sqlite3; sqlite3.connect('/root/.nox/tmpl.db').execute(\"VACUUM INTO '/root/.nox/tmpl-clean.db'\")"
```

### 第 3 步：跑迁移

```sh
cd ~/.nox
python3 ~/workspace/AiGo迁移包/scripts/migrate-schema.py \
  --src src.db \
  --dst-template tmpl-clean.db \
  --out out.db
```

预期输出：

```
schema  ：源=68  目标=64
  ai_providers     迁入     8 行，共有列 31，丢弃列 ['useResponseWebSocket']
  chat_sessions    迁入    25 行，共有列 21，丢弃列 ['workflowId', ...]
  agent_messages   迁入 15004 行，共有列 22，丢弃列 无
  端口修正：4 条供应商

--- 自检 ---
  user_version = 64（应为目标端值 64）
  ai_providers     源=8  输出=8  OK
  ...
[OK] 迁移完成
```

### 第 4 步：写回目标 App

```sh
# 宿主 Root 通道
am force-stop com.aigo
cp /data/user/0/com.nox/files/nox/out.db /data/user/0/com.aigo/databases/nox_agent_db
rm -f /data/user/0/com.aigo/databases/nox_agent_db-wal \
      /data/user/0/com.aigo/databases/nox_agent_db-shm
chown 10696:10696 /data/user/0/com.aigo/databases/nox_agent_db
am start -n com.aigo/com.nox.MainActivity
```

## 参数说明

| 参数 | 说明 |
|---|---|
| `--src` | 源数据库（Nox 的副本） |
| `--dst-template` | 目标 App 重建的空库（schema 版本正确） |
| `--out` | 输出文件 |
| `--tables` | 要迁的表，默认 `ai_providers,chat_sessions,agent_messages` |
| `--port-map` | 端口映射，默认 `18798:18791,8790:8788` |
| `--keep-target-port` | 不改端口（默认会改） |

## 端口为什么必须改

两个 App 共用宿主 loopback，端口必须错开：

| App | 面板 | 网关 | 反代 |
|---|---|---|---|
| Nox | 18797 | 18798 | 8790 |
| AiGo | 18790 | 18791 | 8788 |

从 Nox 复制来的 `ai_providers.baseUrl` 会带 Nox 的端口，脚本自动改成 AiGo 的。

## 会丢什么

被丢弃的列都是**目标端 schema 里不存在的字段**，属于 AiGo 尚未实现的功能：

| 表 | 丢弃的列 | 影响 |
|---|---|---|
| `ai_providers` | `useResponseWebSocket` | 不支持 WebSocket 传输 |
| `chat_sessions` | `workflowId`、`workflowState` | 工作流功能不可用 |
| `chat_sessions` | `backendInstanceId` | 后端实例关联丢失 |
| `chat_sessions` | `workspaceId` | 多工作区支持丢失 |
| `chat_sessions` | `fastMode` | 快速模式丢失 |

**消息正文、会话标题、供应商配置、账号凭证都不丢。**

## 根本解法

长期看应该把上游 65~68 的迁移合入 AiGo，让两边 schema 一致。做完后本脚本就不再需要（但仍可用于防意外）。

见 `memory/aigo-project.md` 的「待办事项 → P0」。

## 踩过的坑

1. **不能删 `-wal` 再写库**：WAL 里的未 checkpoint 事务会丢。曾因此丢失 52 条消息。拷库前先 `PRAGMA wal_checkpoint(TRUNCATE)`。
2. **`VACUUM INTO` 之前库必须是可写的**：只读副本上执行会报 `attempt to write a readonly database`。
3. **模板库不能是 4096 字节的空壳**：那只是文件头，表结构还在 WAL 里。必须 `VACUUM INTO` 导出。
4. **写回后要删 `-wal` / `-shm`**：否则 App 会读到旧事务，覆盖新数据。
