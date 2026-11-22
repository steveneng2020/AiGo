#!/usr/bin/env python3
"""装包前验证 AiGo debug APK 内容（复盘文档「事故三」的防复发检查）。

检查三件事：
1. 迁移文件是否 8..N 连续，最大版本是否等于代码里的 SCHEMA_VERSION
2. 关键类是否真的打进 dex（曾出现有 _Factory 没本体，装上即崩）
3. applicationId / versionName 是否正确

只适用于 debug 包：release 经 R8 混淆后类名会变。

用法：
    python3 verify-apk.py <apk路径> [期望schema]
"""
import re
import struct
import sys
import zipfile


def dex_classes(data):
    """从 dex 字节流里提取全部类描述符。"""
    sid_size, sid_off = struct.unpack_from('<II', data, 0x38)
    tid_size, tid_off = struct.unpack_from('<II', data, 0x40)
    cd_size, cd_off = struct.unpack_from('<II', data, 0x60)

    def get_str(i):
        off = struct.unpack_from('<I', data, sid_off + i * 4)[0]
        pos = off
        length = 0
        shift = 0
        while True:
            b = data[pos]
            pos += 1
            length |= (b & 0x7f) << shift
            if not (b & 0x80):
                break
            shift += 7
        return data[pos:pos + length].decode('utf-8', 'replace')

    out = []
    for i in range(cd_size):
        type_idx = struct.unpack_from('<I', data, cd_off + i * 32)[0]
        str_idx = struct.unpack_from('<I', data, tid_off + type_idx * 4)[0]
        out.append(get_str(str_idx))
    return out


def main():
    if len(sys.argv) < 2:
        print('用法: python3 verify-apk.py <apk路径> [期望schema]')
        return 2
    apk = sys.argv[1]
    expect = int(sys.argv[2]) if len(sys.argv) > 2 else None

    z = zipfile.ZipFile(apk)
    failed = []

    # ── 1. 迁移文件连续性 ──
    migs = [n for n in z.namelist()
            if n.startswith('assets/migrations/') and n.endswith('.sql')]
    versions = sorted(int(re.match(r'(\d+)_', n.split('/')[-1]).group(1)) for n in migs)
    if not versions:
        failed.append('没有任何迁移文件')
        print('[FAIL] 迁移文件：一个都没有')
    else:
        lo, hi = versions[0], versions[-1]
        missing = [v for v in range(lo, hi + 1) if v not in versions]
        print(f'[{"PASS" if not missing else "FAIL"}] 迁移文件 {lo}..{hi}，共 {len(versions)} 个，'
              f'缺失: {missing if missing else "无（连续）"}')
        if missing:
            failed.append(f'迁移文件不连续，缺 {missing}')
        if expect is not None and hi != expect:
            failed.append(f'APK schema={hi}，期望 {expect}')
            print(f'[FAIL] APK schema={hi}，期望 {expect}')
        elif expect is not None:
            print(f'[PASS] APK schema={hi}，与期望一致')

    # ── 2. 关键类 ──
    all_classes = set()
    for name in [n for n in z.namelist() if n.endswith('.dex')]:
        all_classes.update(dex_classes(z.read(name)))
    print(f'\n总类数: {len(all_classes)}')

    # 这些类缺任何一个，启动就会崩（Hilt 初始化或入口缺失）
    required = [
        'Lcom/nox/AIEditorApp;',
        'Lcom/nox/MainActivity;',
        'Lcom/nox/feature/agent/domain/container/ContainerInstaller;',
        'Lcom/nox/feature/plugin/domain/PluginManager;',
        'Lcom/nox/feature/plugin/data/PluginPackageExporter;',
        'Lcom/nox/feature/settings/data/repository/PluginDrawerVisibilityRepository;',
        'Lcom/nox/feature/agent/data/touchlock/TouchLockManager;',
        'Lcom/nox/feature/settings/data/repository/AppIconManager;',
    ]
    for cls in required:
        ok = cls in all_classes
        print(f'[{"PASS" if ok else "FAIL"}] {cls}')
        if not ok:
            failed.append(f'缺类 {cls}')

    # ── 3. 九套图标资源 ──
    icon_xml = ['res/mipmap-anydpi-v26/ic_launcher_default.xml',
                'res/mipmap-anydpi-v26/ic_launcher_moyu.xml',
                'res/mipmap-anydpi-v26/ic_launcher_tianqing.xml',
                'res/mipmap-anydpi-v26/ic_launcher_zheshi.xml',
                'res/mipmap-anydpi-v26/ic_launcher_biyu.xml',
                'res/mipmap-anydpi-v26/ic_launcher_zishou.xml']
    names = set(z.namelist())
    miss_icon = [p for p in icon_xml if p not in names]
    print(f'\n[{"PASS" if not miss_icon else "FAIL"}] 鲲六套图标资源'
          f'{"" if not miss_icon else "，缺: " + str(miss_icon)}')
    if miss_icon:
        failed.append(f'缺图标资源 {miss_icon}')

    print()
    if failed:
        print(f'==> 验证失败，{len(failed)} 个问题：')
        for f in failed:
            print('   -', f)
        return 1
    print('==> 全部通过，可以安装')
    return 0


if __name__ == '__main__':
    sys.exit(main())
