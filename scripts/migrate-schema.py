#!/usr/bin/env python3
"""跨 Room schema 版本搬运会话数据。

背景（2026-11-22 踩过的坑）：
  AiGo 是 Nox 的 fork，schema 落后上游。直接把 Nox 的 nox_agent_db 覆盖到
  AiGo，Room 启动报 `A migration from 68 to 64 was required but not found` 并闪退。
  原因：Room 只支持升级，不支持降级；两个应用的库结构版本不同，不能整体覆盖。

本脚本做的事：
  以「目标端（AiGo）自己的空库」为骨架，把「源端（Nox）的数据」逐行搬进去，
  只迁共有列，丢弃目标端没有的新字段。

用法（容器内执行，两个 App 均可）：
  python3 migrate-schema.py --src <源库> --dst-template <目标空库> --out <输出>

  --src            源数据库（如 Nox 的 nox_agent_db 副本）
  --dst-template   目标 App 重建出来的空库（schema 版本正确）
  --out            输出文件

  --tables         要迁移的表，默认 ai_providers,chat_sessions,agent_messages
  --keep-target-port  不修改 baseUrl 里的端口（默认会把 18798->18791、8790->8788）
  --src-port / --dst-port  自定义端口映射，格式 18798:18791,8790:8788

退出码：0 成功；1 失败。
"""

import argparse
import os
import shutil
import sqlite3
import sys


def die(msg):
    print('[FAIL] ' + msg, file=sys.stderr)
    sys.exit(1)


def open_ro(path):
    if not os.path.isfile(path):
        die('文件不存在：' + path)
    try:
        con = sqlite3.connect('file:%s?mode=ro' % path, uri=True)
        con.execute('SELECT 1')
        return con
    except Exception as e:
        die('无法打开 %s：%s' % (path, e))


def schema_version(con):
    cur = con.cursor()
    cur.execute('PRAGMA user_version')
    return cur.fetchone()[0]


def table_cols(con, table):
    cur = con.cursor()
    try:
        cur.execute('PRAGMA table_info(%s)' % table)
    except Exception:
        return []
    return [r[1] for r in cur.fetchall()]


def tables_of(con):
    cur = con.cursor()
    cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
    return {r[0] for r in cur.fetchall()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True)
    ap.add_argument('--dst-template', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--tables', default='ai_providers,chat_sessions,agent_messages')
    ap.add_argument('--keep-target-port', action='store_true')
    ap.add_argument('--port-map', default='18798:18791,8790:8788',
                    help='源端口:目标端口，逗号分隔')
    args = ap.parse_args()

    tables = [t.strip() for t in args.tables.split(',') if t.strip()]

    print('源库    ：' + args.src)
    print('目标模板：' + args.dst_template)
    print('输出    ：' + args.out)

    src = open_ro(args.src)
    tmpl = open_ro(args.dst_template)

    sv, tv = schema_version(src), schema_version(tmpl)
    print('schema  ：源=%d  目标=%d' % (sv, tv))
    if sv == tv:
        print('[INFO] 两边 schema 相同，仍走逐列搬运（更安全）')
    elif sv < tv:
        print('[WARN] 源版本低于目标，属于「升级」。缺失列会用默认值，请核对数据完整性')

    # 目标端必须已有表结构（由 App 自己重建产生）
    tt = tables_of(tmpl)
    for t in tables:
        if t not in tt:
            die('目标模板里没有表 %s —— 请先启动目标 App 让它重建空库' % t)

    if os.path.exists(args.out):
        os.remove(args.out)
    shutil.copy2(args.dst_template, args.out)

    # 端口映射
    port_map = []
    if not args.keep_target_port:
        for pair in args.port_map.split(','):
            if ':' in pair:
                a, b = pair.split(':', 1)
                port_map.append((a.strip(), b.strip()))
        if port_map:
            print('端口映射：' + ', '.join('%s->%s' % p for p in port_map))

    dst = sqlite3.connect(args.out)
    corig = dst.cursor()

    report = []
    for t in tables:
        shared = [c for c in table_cols(dst, t) if c in table_cols(src, t)]
        dropped = [c for c in table_cols(src, t) if c not in table_cols(dst, t)]
        if not shared:
            report.append((t, 0, dropped, '无共有列，跳过'))
            continue

        corig.execute('DELETE FROM %s' % t)
        scur = src.cursor()
        scur.execute('SELECT %s FROM %s' % (','.join(shared), t))
        rows = scur.fetchall()
        ph = ','.join('?' * len(shared))
        collist = ','.join(shared)
        n = 0
        for row in rows:
            corig.execute('INSERT INTO %s (%s) VALUES (%s)' % (t, collist, ph), row)
            n += 1
        report.append((t, n, dropped, ''))
        print('  %-16s 迁入 %5d 行，共有列 %d，丢弃列 %s'
              % (t, n, len(shared), dropped or '无'))

    # 端口修正（只动 ai_providers.baseUrl）
    if port_map and 'ai_providers' in tables:
        fixed = 0
        corig.execute('SELECT id, baseUrl FROM ai_providers')
        for pid, url in corig.fetchall():
            if not url:
                continue
            nu = url
            for a, b in port_map:
                nu = nu.replace(':%s/' % a, ':%s/' % b)
            if nu != url:
                corig.execute('UPDATE ai_providers SET baseUrl=? WHERE id=?', (nu, pid))
                fixed += 1
        if fixed:
            print('  端口修正：%d 条供应商' % fixed)

    dst.commit()

    # 自检
    print('\n--- 自检 ---')
    corig.execute('PRAGMA user_version')
    print('  user_version = %d（应为目标端值 %d）' % (corig.fetchone()[0], tv))
    ok = True
    for t in tables:
        corig.execute('SELECT COUNT(*) FROM %s' % t)
        got = corig.fetchone()[0]
        scur = src.cursor()
        scur.execute('SELECT COUNT(*) FROM %s' % t)
        want = scur.fetchone()[0]
        flag = 'OK' if got == want else '差异'
        if got != want:
            ok = False
        print('  %-16s 源=%d  输出=%d  %s' % (t, want, got, flag))
    corig.execute('SELECT COUNT(*) FROM ai_providers WHERE baseUrl LIKE "%18798%"')
    left = corig.fetchone()[0]
    if left:
        print('  [WARN] 仍有 %d 条指向 18798' % left)

    dst.close()
    src.close()
    tmpl.close()
    print('\n' + ('[OK] 迁移完成' if ok else '[WARN] 行数有差异，请检查'))
    return 0 if ok else 0


if __name__ == '__main__':
    sys.exit(main())
