<p align="center">
  <img src="docs/icon-aigo.webp" width="112" alt="AiGo" />
  <h1 align="center">AiGo</h1>
  <p align="center">
    AI coding tool and mobile workstation for Android, a fork of Nox
    <br />
    <a href="README.md">中文</a> · <a href="README.en.md">English</a>
  </p>
</p>

> [!IMPORTANT]
> **AiGo is an unofficial fork of Nox, released under GPL-3.0.**
>
> - **This repository**: [github.com/steveneng2020/AiGo](https://github.com/steveneng2020/AiGo)
> - **Direct upstream**: [github.com/xzyy03/Nox](https://github.com/xzyy03/Nox) (the fork parent)
> - **Further upstream**: [github.com/jieapi/aicode](https://github.com/jieapi/aicode) (what Nox forked from)
> - **License**: [GPL-3.0](LICENSE), same as upstream; this fork is open source as well
> - **Package name**: `com.aigo` (Nox uses `com.nox`, so both can coexist on one device)
> - **Contact**: steveneng@163.com

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android Platform" />
  <a href="https://github.com/steveneng2020/AiGo/releases"><img src="https://img.shields.io/github/v/release/steveneng2020/AiGo?display_name=tag&include_prereleases" alt="Latest Release" /></a>
</p>

<p align="center">
  <table>
    <tr>
      <td align="center"><img src="docs/screenshots/home.jpg" alt="AiGo new session - pick a model and start" width="210"/></td>
      <td align="center"><img src="docs/screenshots/chat.jpg" alt="AiGo chat - tool calls and reasoning" width="210"/></td>
      <td align="center"><img src="docs/screenshots/container.jpg" alt="AiGo containers - any distro" width="210"/></td>
      <td align="center"><img src="docs/screenshots/models.jpg" alt="AiGo providers - any vendor" width="210"/></td>
    </tr>
    <tr>
      <td align="center">New session · pick a model</td>
      <td align="center">Chat · tool calls</td>
      <td align="center">Containers · any distro</td>
      <td align="center">Models · any provider</td>
    </tr>
    <tr>
      <td align="center"><img src="docs/screenshots/git.jpg" alt="AiGo Git - status, branches and commits" width="210"/></td>
      <td align="center"><img src="docs/screenshots/drawer.jpg" alt="AiGo sidebar - sessions and entries" width="210"/></td>
      <td align="center"><img src="docs/screenshots/settings.jpg" alt="AiGo settings - preferences and AI config" width="210"/></td>
      <td align="center"><img src="docs/screenshots/about.jpg" alt="AiGo about - version and updates" width="210"/></td>
    </tr>
    <tr>
      <td align="center">Git · status &amp; commits</td>
      <td align="center">Sidebar · sessions</td>
      <td align="center">Settings · preferences</td>
      <td align="center">About · version &amp; updates</td>
    </tr>
  </table>
</p>

---

## Overview

AiGo is a universal AI coding agent that runs on Android, packing a full Linux development environment into your phone: it bundles a terminal, and the AI agent can read and write files, run shell commands and run build tools, so writing, debugging and building all happen on-device. A remote SSH server can also serve as the execution backend, turning your phone into a mobile workstation for remote projects.

There is nothing to set up beforehand: install the app and configure a model under AI Vendors. Container images are added or downloaded manually the first time. AiGo ships no models and locks you into no vendor: it supports the OpenAI / Anthropic / Gemini protocols plus custom providers, with models, keys and endpoints all configured by you.

## Relationship to upstream

AiGo is built on [Nox](https://github.com/xzyy03/Nox), which in turn is built on
[AiCode](https://github.com/jieapi/aicode). Every layer in that chain is GPL-3.0, and the
modifications are open source as well:

```
jieapi/aicode  ──▶  xzyy03/Nox  ──▶  steveneng2020/AiGo
  original           direct upstream        this project
```

AiGo and Nox **can coexist on the same device** (different package names, neither overwrites
the other). Upstream Nox updates keep flowing into AiGo.

## What AiGo changes

### Differences from Nox

| Item | Nox | AiGo |
| --- | --- | --- |
| App name | Nox | AiGo |
| Package | `com.nox` | `com.aigo` (both can coexist on one device) |
| App icon | one set | nine switchable sets (six Kun + three originals) |
| Usage detail | one line of small text under each reply | full panel + per-turn breakdown card |
| Update check | points at the Nox repo | points at this project |
| Contact | QQ group | steveneng@163.com |

The table above is maintained by hand. Everything else in this file is a summary —
the authoritative list of changes is [CHANGELOG.md](CHANGELOG.md).

### Additions and improvements

This fork is under active development. Rather than mirroring the full release notes here
(which would drift out of sync), the detailed per-version changelog lives in one place:

- **[CHANGELOG.md](CHANGELOG.md)** — every version, in Chinese, with what changed and why
- **In-app**: open **About → What's new**. The notes ship inside the APK, so they work offline

A few of the larger additions, so you know what to expect beyond upstream:

- **Context usage panel** — full breakdown of token usage, cache hit rate, and per-turn details,
  instead of a single line of small text under each reply
- **Nine switchable app icon sets** — pick one under About; launcher and notification icons
  stay in sync
- **Accidental-touch guard** — dims the screen and blocks stray taps, with a configurable
  hold-to-exit duration
- **Plugin management** — export, update, and block plugins from both the drawer and the store

> The Chinese README carries the complete, auto-generated release highlights.
> See [README.md](README.md).

## Features

### AI Agent

- **AI Agent** — Built-in tools for file read/write/edit, shell execution, background terminal, code & web search, image recognition, todo lists, and more; streaming output with live Markdown rendering and automatic context compression
- **Built-in browser** — A multi-tab WebView browser the AI can drive to navigate, click, fill forms and take screenshots to complete web tasks; supports night mode and local file previews
- **Parallel subagents** — Spawn subagents with their own isolated context to research, review or compare approaches in the background without blocking the current chat; a read-only **Explore** subagent is built in, and you can define your own with a custom model, tool set and prompt
- **Three run modes** — BUILD for normal development, PLAN for read-only planning (writes blocked at the tool layer), AUTO to approve everything without prompts — pick the permission scope you trust
- **Checkpoints & Undo** — Snapshots are recorded before the agent edits code; one-tap rollback of code, chat history, or both
- **Skills & Auto Memory** — Global/project-level skills and long-term memory let the AI reuse experience and project conventions across sessions
- **MCP Protocol** — Connect to local (stdio) or remote (HTTP) MCP servers to dynamically extend AI tool capabilities
- **Tool permissions & custom prompts** — Per-tool authorization rules; system prompts can be overridden by the user and survive app upgrades

### Models & Providers

- **Model-agnostic** — No bundled models and no vendor lock-in: models, keys and endpoints are yours to configure
- **Protocol support** — Compatible with the OpenAI / Anthropic / Gemini protocols, with presets for several official providers and support for custom ones; model lists and pricing are customizable
- **Multiple keys** — Configure several keys per provider with sequential or round-robin rotation; reasoning effort is adjustable

### Development Environment

- **Built-in Terminal & Container** — A local Linux container built on Termux and PRoot with a built-in Alpine image; supports custom rootfs images and host directory mounts; multi-tab terminals that can stay alive in the background
- **Remote SSH Mode** — Use a remote SSH server as the execution backend: commands, files and terminal all act on the remote project
- **Shizuku backend** — With Shizuku, the AI can run shell and adb commands on the host Android system to debug or operate other devices (requires per-call approval)
- **File tree & code editor** — An indented file tree that opens files in a full-screen editor with syntax highlighting for mainstream languages and Markdown preview; `file:line` links in AI replies jump straight to that line, in both local and remote SSH workspaces
- **Git Integration** — See what the AI changed, commit to leave a restore point, switch branches, push to remote. Status, diffs, history and tags are all visual; if the AI goes off track, roll back to the previous commit
- **Workspace Sync** — SFTP / FTP synchronization with a built-in FTP server for desktop file management

### Experience

- **Tablet & large screen** — Layout adapts to window width: a persistent sidebar on wide screens with code or terminal beside the chat, falling back to a single pane when narrow
- **Token stats** — Usage and cost estimates per provider and model, with a drill-down into individual calls
- **Appearance & language** — Light/dark themes, preset color schemes, Material You colors, custom background images, and a bilingual (Chinese/English) UI
- **Network proxy** — Configure a global proxy and per-provider proxies separately
- **Backup & Restore** — Encrypted export/import of provider configs, credentials, chat history and workspace files

## Getting Started

| Item | Description |
|------|-------------|
| System requirements | Android 8.0+ (API 26), arm64-v8a / x86_64 |
| Download | [GitHub Releases](https://github.com/steveneng2020/AiGo/releases/latest): pick `arm64` for real devices, `x86_64` for emulators, `universal` when unsure |
| Quick start | Settings → AI Vendors to add a model → Container & Images to pick local or SSH → new session and chat |
| Changelog | [Releases](https://github.com/steveneng2020/AiGo/releases) (all versions & notes) |
| User guide | [Online docs](https://github.com/steveneng2020/AiGo/tree/main/docs-site/docs): quick start, user manual and advanced guides (same content as the in-app docs) |

## Versioning

Released builds use the tag itself as the version, e.g. `1.0.0`. Builds you compile yourself
carry a `+date` suffix (e.g. `1.0.0+20261121`) to tell one build from another; the date does not
participate in comparison and does not affect update prompts.

The in-app **About** page lists what changed in each version. Those notes ship inside the APK,
so they work offline too.

## Feedback & Contribution

- **Contact**: steveneng@163.com
- **Bug reports**: open an [Issue](https://github.com/steveneng2020/AiGo/issues) with reproduction steps, device model and OS version
- **Feature requests**: discuss your ideas in [Issues](https://github.com/steveneng2020/AiGo/issues)
- **Contributing**: submit a [Pull Request](https://github.com/steveneng2020/AiGo/pulls)

## Star

If AiGo is helpful to you, give it a [Star](https://github.com/steveneng2020/AiGo).

## Acknowledgements

This project builds on:

- [jieapi/aicode](https://github.com/jieapi/aicode) — the original project
- [xzyy03/Nox](https://github.com/xzyy03/Nox) — AiGo's direct upstream
- [OpenCode](https://github.com/anomalyco/opencode) — Terminal-based AI coding tool, the core inspiration for this project
- [Termux](https://github.com/termux/termux-app) — Android terminal emulator, provided terminal components and PRoot solution
- [Kelivo](https://github.com/Chevey339/kelivo) — Cross-platform LLM chat client, AI conversation UI design reference

## Disclaimer

AiGo is provided "as is", for learning, research and lawful software development only. Using it for any illegal or infringing purpose is strictly prohibited, including but not limited to unauthorized access to, cracking or reverse engineering of others' software or systems, circumventing technological protection measures, stealing or leaking others' data, or distributing malicious code.

You bear all risks and responsibility for your use of this software. The authors and contributors shall not be liable for any loss arising from your use of, or inability to use, this software, nor for any act you perform using it or its consequences.

If you do not agree with this disclaimer, please stop using the software and uninstall it immediately.

## License

This project is licensed under [GPL-3.0](LICENSE).
