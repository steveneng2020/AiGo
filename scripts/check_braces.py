"""Kotlin 括号平衡检查：正确跳过注释、字符串、字符字面量与原始字符串。

为什么不能用简单计数：源码里 `"}"`、`'{'`、`// }`、块注释中的括号都会干扰，
简单 count 会把合法文件误判为不平衡（实测误报过 3 个能正常编译的文件）。
"""
import sys

def balance(path):
    try:
        s = open(path, encoding='utf-8', errors='ignore').read()
    except OSError:
        return 0
    i = 0
    n = len(s)
    depth = 0
    state = 'code'
    while i < n:
        c = s[i]
        nxt = s[i + 1] if i + 1 < n else ''
        if state == 'code':
            if c == '/' and nxt == '/':
                state = 'line_comment'; i += 2; continue
            if c == '/' and nxt == '*':
                state = 'block_comment'; i += 2; continue
            if s.startswith('"""', i):
                state = 'raw_str'; i += 3; continue
            if c == '"':
                state = 'str'; i += 1; continue
            if c == "'":
                state = 'char'; i += 1; continue
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
            i += 1; continue
        if state == 'line_comment':
            if c == '\n':
                state = 'code'
            i += 1; continue
        if state == 'block_comment':
            if c == '*' and nxt == '/':
                state = 'code'; i += 2; continue
            i += 1; continue
        if state == 'raw_str':
            if s.startswith('"""', i):
                state = 'code'; i += 3; continue
            i += 1; continue
        if state == 'str':
            if c == '\\':
                i += 2; continue
            if c == '"':
                state = 'code'
            i += 1; continue
        if state == 'char':
            if c == '\\':
                i += 2; continue
            if c == "'":
                state = 'code'
            i += 1; continue
    return depth

bad = []
for f in sys.argv[1:]:
    d = balance(f)
    if d != 0:
        bad.append((f, d))
if bad:
    for f, d in bad:
        print(f'{f} (深度 {d})')
    sys.exit(1)
sys.exit(0)
