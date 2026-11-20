<p align="center">
  <img src="docs/icon-nox.webp" width="112" alt="AiGo" />
  <h1 align="center">AiGo</h1>
  <p align="center">
    手机上的 AI 编程工具与移动工作站 · 基于 Nox（<a href="https://github.com/xzyy03/Nox">xzyy03/Nox</a>）二次开发
    <br />
    <a href="README.md">中文</a> · <a href="README.en.md">English</a>
  </p>
</p>

> [!IMPORTANT]
> **AiGo 是 Nox 的二次开发版（非官方），遵循 GPL-3.0 开源。**
>
> - **本项目**：[github.com/steveneng2020/AiGo](https://github.com/steveneng2020/AiGo)
> - **直接上游**：[github.com/xzyy03/Nox](https://github.com/xzyy03/Nox)（AiGo 的 fork 源，保持 fork 关系）
> - **更上游**：[github.com/jieapi/aicode](https://github.com/jieapi/aicode)（Nox 的二改基础）
> - **许可证**：[GPL-3.0](LICENSE)（与上游一致，二次开发同样开源）
> - **包名**：`com.aigo`（与 Nox 的 `com.nox` 不同，两者可同机共存）
> - **联系邮箱**：steveneng@163.com

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android Platform" />
  <a href="https://github.com/steveneng2020/AiGo/releases"><img src="https://img.shields.io/github/v/release/steveneng2020/AiGo?display_name=tag&include_prereleases" alt="Latest Release" /></a>
</p>

---

## 简介

AiGo 是运行在 Android 上的通用 AI Coding Agent，把一套完整的 Linux 开发环境装进手机：内置终端，AI Agent 能读写文件、执行 Shell 命令、运行构建工具，写代码、调试到构建都在手机本地完成；也可改用远程 SSH 服务器作为执行后端，把手机变成远程项目的移动工作站。

上手只需装上 App、在「AI 供应商」配好模型即可直接开发；容器镜像首次使用需在设置里手动添加或下载。AiGo 不内置模型、不绑定供应商，支持 OpenAI / Anthropic / Gemini 三类协议与自定义供应商，模型、密钥与端点都由你自行配置。

## 与上游的关系

AiGo 的代码来源是一条两层的二改链：

```
jieapi/aicode  ──(Nox 二次开发)──▶  xzyy03/Nox  ──(AiGo 二次开发)──▶  steveneng2020/AiGo
   原始项目                          直接上游（fork 源）                  本项目
```

- AiGo 从 Nox 直接 fork，**保持完整的 fork 关系**，上游 Nox 的更新可以持续合入。
- AiGo 相对 Nox 的改动集中在**应用层品牌**与**实用功能优化**，源码包名（`com.nox`）保持不变，以此换取与上游合并的低冲突成本。
- 如需跟进更上游（AiCode）的改动，正规路径是等 Nox 合入后再同步；跨层直接合并会破坏与 Nox 的 fork 关系，本项目不做。

## 相对上游 Nox 的改动

### 品牌

| 项 | Nox | AiGo |
| --- | --- | --- |
| 应用名 | Nox | AiGo |
| 包名 | `com.nox` | `com.aigo` |
| 更新检查 | 指向 xzyy03/Nox | 指向本仓库 |
| 关于页链接 | 上游仓库 | 本项目仓库 |
| 联系渠道 | QQ 群 | 邮箱 steveneng@163.com |

### 功能

- **运行中也能添加附件** — AI 正在处理时，输入框的 `+` 不再置灰。可以边等边补图片或文件，随排队消息一起提交，不必等本轮结束。
- **通知栏图标跟随应用图标** — 通知与后台服务的小图标改为按「图标设置」里选中的图标解析，换图标后已发出的通知会自动重建，不再是固定的系统占位图。
- **侧边栏插件分组** — 插件入口从设置组中独立成组，带分组标题、来源插件副标题与「更多插件」入口。
- **关于页更新内容卡片** — 展示最近版本的更新日志，可展开查看全部历史版本。
- **版本号由 git 驱动** — `versionName` 由 tag 推导、`versionCode` 由提交数递增，发版不再手改版本号；支持本地调试时用 `-PversionCodeOverride` / `-PversionNameOverride` 固定版本号反复覆盖安装。

## 功能特性

### AI Agent

- **AI Agent** — 内置文件读写与编辑、Shell 执行、后台终端、代码与网页搜索、图片识别、待办清单等工具；流式输出并实时渲染 Markdown，长对话自动压缩上下文
- **内置浏览器** — 内置多标签 WebView 浏览器，AI 可导航、点击、填表与截图，直接在网页上完成操作；支持夜间模式与本地文件预览
- **子代理并行** — 主会话可派生独立上下文的子代理在后台并行调研、审查或对比方案，不阻塞当前对话；内置只读的 Explore 子代理，也可自定义模型、工具集与提示词
- **检查点与撤销** — Agent 改代码前自动记录文件快照，对话中可一键回滚代码、对话或两者
- **技能与自动记忆** — 支持全局/项目级技能（Skills）与长期记忆，AI 可跨会话复用经验与项目约定
- **MCP 协议** — 支持连接本地（stdio）与远程（HTTP）MCP 服务器，动态扩展 AI 工具能力
- **工具授权与自定义提示词** — 逐工具配置授权规则，系统提示词支持用户覆盖且 App 升级不丢失

### 模型与供应商

- **模型无关** — 不内置模型、不绑定供应商，模型、密钥与端点自行配置
- **协议兼容** — 兼容 OpenAI / Anthropic / Gemini 三类协议，内置多家官方预设，也支持自定义供应商，模型列表与单价可自定义
- **多 Key 轮换** — 同一供应商可配多个 Key，按顺序或轮询自动轮换，思考强度可调

### 开发环境

- **内置终端与容器** — 基于 Termux 与 PRoot 的本地 Linux 容器，内置 Alpine 镜像，支持导入自定义 rootfs、挂载宿主目录；终端多标签、可后台常驻
- **远程 SSH 模式** — 把远程服务器作为执行后端，命令、文件与终端都作用于远端项目
- **Shizuku 执行后端** — 借助 Shizuku，AI 可在宿主 Android 系统上执行 shell 与 adb 命令，用于调试或操作其他设备（需逐次授权确认）
- **文件树与代码编辑器** — 缩进式文件树点开即进全屏编辑器，支持主流语言语法高亮与 Markdown 预览；AI 回复里的 `文件:行号` 链接可直接跳转到对应行
- **Git 集成** — 可视化管理状态、分支、提交历史、差异与标签，支持暂存与回退改动、署名与凭据配置
- **工作区同步** — 支持 SFTP / FTP 同步，内置 FTP 服务端方便电脑端管理文件

### 使用体验

- **平板与大屏适配** — 按窗口宽度自适应：宽屏常驻侧边栏，聊天旁并排显示代码或终端；变窄时退回单栏
- **Token 统计** — 按渠道与模型统计用量、估算费用，可下钻查看调用明细
- **外观与语言** — 主题明暗、预设配色、自定义背景图、多套应用图标，中英双语界面
- **网络代理** — 支持全局代理与供应商级代理分别配置
- **备份与还原** — 加密导出/导入供应商配置、凭据、聊天历史与工作区文件

## 快速开始

| 项目 | 说明 |
| --- | --- |
| 系统要求 | Android 8.0+（API 26），arm64-v8a / x86_64 |
| 下载地址 | [GitHub Releases](https://github.com/steveneng2020/AiGo/releases/latest)：真机选 `arm64` 包，模拟器选 `x86_64` 包，不确定选 `universal` |
| 快速上手 | 「设置 → AI 供应商」配模型 →「容器与镜像」选本地或 SSH → 新建会话开始对话 |
| 更新记录 | [Releases](https://github.com/steveneng2020/AiGo/releases)，App 内「关于」页也可查看 |
| 使用文档 | [docs-site/docs](docs-site/docs)（与 App 内置文档同源） |

## 版本号规则

版本号的唯一事实源是 Git，不需要手改 `build.gradle.kts`：

| 场景 | versionName | versionCode |
| --- | --- | --- |
| 打 tag `v1.2.3` | `1.2.3` | 该提交的提交总数 |
| 无 tag（日常提交） | `1.0.0-dev.N+<hash>` | 提交总数 |
| 本地调试（固定版本） | `-PversionNameOverride` 指定 | `-PversionCodeOverride` 指定 |

发版：`git tag -a v1.0.0 && git push origin v1.0.0`，CI 自动构建三种架构并发布 Release。

本地反复构建又不升版本：

```bash
sh gradlew :app:assembleUniversalDebug \
  -PversionCodeOverride=14 -PversionNameOverride=1.0.0-dev
```

**注意**：不要对 `main` 做 rebase / squash / force push——`versionCode` 由提交数计算，改写历史会导致版本号回退、新包装不上。

## 构建

```bash
./gradlew :app:assembleUniversalDebug     # 通用调试包（arm64 + x86_64）
./gradlew :app:assembleArmsoloDebug       # 仅 arm64
```

发布签名读取 `app/keystore.properties`，CI 从 GitHub Secrets 还原。

## 反馈与贡献

- **联系邮箱**：steveneng@163.com
- **Bug 反馈**：到 [Issues](https://github.com/steveneng2020/AiGo/issues) 提交，附上复现步骤、设备型号与系统版本
- **功能建议**：欢迎先在 [Issues](https://github.com/steveneng2020/AiGo/issues) 讨论
- **贡献代码**：欢迎提交 [Pull Request](https://github.com/steveneng2020/AiGo/pulls)

## 致谢

本项目建立在以下项目之上：

- [jieapi/aicode](https://github.com/jieapi/aicode) — 原始项目
- [xzyy03/Nox](https://github.com/xzyy03/Nox) — AiGo 的直接上游
- [OpenCode](https://github.com/anomalyco/opencode) — 终端 AI 编码工具，核心灵感来源
- [Termux](https://github.com/termux/termux-app) — Android 终端模拟器，提供终端组件与 PRoot 方案
- [Kelivo](https://github.com/Chevey339/kelivo) — 跨平台 LLM 聊天客户端，AI 对话界面设计参考

## 免责声明

AiGo 按「现状」提供，仅供学习、研究与合法的软件开发用途。严禁将本软件用于任何违法或侵权用途，包括但不限于未经授权入侵、破解、逆向工程他人软件或系统，绕过技术保护措施，窃取或泄露他人数据，传播恶意代码等侵害他人合法权益的行为。

使用者应自行承担使用本软件的全部风险与责任；对于使用者因使用或无法使用本软件而产生的任何损失，以及其利用本软件实施的任何行为及后果，作者与贡献者均不承担任何责任。

若不同意上述声明，请立即停止使用并卸载本软件。

## 开源协议

本项目基于 [GPL-3.0](LICENSE) 协议开源。依据 GPL-3.0，修改版本同样以 GPL-3.0 发布，并保留原作者的版权声明（见 [NOTICE](NOTICE)）。
