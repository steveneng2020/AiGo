#!/usr/bin/env python3
"""检查某个 commit 在 GitHub Actions 上的运行结果（推送后闭环用）。

只用标准库，靠 curl 拿数据后由本脚本判断，便于在 PRoot 容器内跑。

判据与处置：
  未触发        —— push 事件没投递。处理：Actions 页手动 Run workflow，或改动一行再推。
  有 running    —— 还在跑。处理：等几分钟再查，不要急着重推。
  有 failure    —— 用 logs 子命令看红在哪一步，修完再推。
  全部 success  —— 闭环成立。

用法：
  check_actions.py --sha <sha> [--repo owner/name] [--wait 分钟] [--json]
"""
import argparse
import json
import subprocess
import sys
import time


def get_token(main_dir):
    """从 git 凭据助手取 token（凭据按目录生效，必须在仓库目录内取）。"""
    try:
        out = subprocess.run(
            ['git', 'credential', 'fill'],
            input='url=https://github.com\n',
            cwd=main_dir, capture_output=True, text=True, timeout=30
        )
    except Exception:
        return None
    for line in out.stdout.splitlines():
        if line.startswith('password='):
            return line[len('password='):].strip() or None
    return None


def api(url, token, retries=3):
    for attempt in range(retries):
        try:
            out = subprocess.run(
                ['curl', '-s', '-m', '25', '-H', f'Authorization: Bearer {token}', url],
                capture_output=True, text=True, timeout=40
            )
            body = out.stdout.strip()
            if body:
                d = json.loads(body)
                # 限流时 GitHub 返回 {"message": "API rate limit exceeded..."}
                if isinstance(d, dict) and 'message' in d and 'runs' not in ' '.join(d.keys()):
                    time.sleep(10 * (attempt + 1))
                    continue
                return d
        except Exception:
            pass
        time.sleep(5 * (attempt + 1))
    return None


def fetch(sha, token, repo):
    # 按 sha 过滤查到的是全量列表，新提交时常在首页；per_page 放 50 防止
    # 同时段多个 workflow 把目标 sha 挤到第二页。
    d = api(f'https://api.github.com/repos/{repo}/actions/runs?per_page=50', token)
    if not d or 'workflow_runs' not in d:
        return None
    return [r for r in d['workflow_runs'] if r.get('head_sha') == sha]


def summarize(runs):
    pending = [r for r in runs if r['status'] != 'completed']
    failed = [r for r in runs if r['conclusion'] not in (None, 'success', 'skipped', 'neutral')]
    ok = [r for r in runs if r['conclusion'] in ('success', 'skipped', 'neutral')]
    return pending, failed, ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--sha', required=True)
    ap.add_argument('--repo', default='steveneng2020/AiGo')
    ap.add_argument('--main-dir', default='/root/.aigo-build')
    ap.add_argument('--wait', type=int, default=0,
                    help='最多等待多少分钟直到全部结束（0=只查一次）')
    ap.add_argument('--json', action='store_true')
    a = ap.parse_args()

    token = get_token(a.main_dir)
    if not token:
        print('SKIP 取不到 GitHub 凭据', file=sys.stderr)
        return 2

    deadline = time.time() + a.wait * 60
    runs = None
    while True:
        runs = fetch(a.sha, token, a.repo)
        if runs is None:
            print('SKIP 无法查询 Actions（网络或限流）', file=sys.stderr)
            return 2
        if not runs or time.time() >= deadline:
            break
        pending, _, _ = summarize(runs)
        if not pending:
            break
        time.sleep(20)

    short = a.sha[:7]
    if not runs:
        print(f'NOTRIGGERED {short} 未产生任何 workflow run')
        return 1

    pending, failed, ok = summarize(runs)
    if a.json:
        json.dump({'sha': short, 'total': len(runs),
                   'pending': [r['name'] for r in pending],
                   'failed': [r['name'] for r in failed],
                   'ok': [r['name'] for r in ok]}, sys.stdout, ensure_ascii=False)
        print()
        return 0 if not failed and not pending else 1

    for r in runs:
        mark = {'success': 'OK  ', 'failure': 'FAIL', 'cancelled': 'CANC',
                'skipped': 'SKIP', 'neutral': 'OK  '}.get(r['conclusion'] or '', 'RUN ')
        print(f"  [{mark}] {r['name']} #{r['run_number']} "
              f"{r['status']}/{r['conclusion']} {r['html_url']}")

    if pending:
        print(f'PENDING {short} 还有 {len(pending)} 个 run 在跑')
        return 1
    if failed:
        print(f'FAILED {short} {len(failed)} 个 run 失败')
        return 1
    print(f'SUCCESS {short} 全部 {len(runs)} 个 run 通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
