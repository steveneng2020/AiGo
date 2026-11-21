<p align="center">
  <img src="docs/icon-nox.webp" width="112" alt="AiGo" />
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

---

## Overview

AiGo is a universal AI coding agent that runs on Android, packing a full Linux development environment into your phone: it bundles a terminal, and the AI agent can read and write files, run shell commands and run build tools, so writing, debugging and building all happen on-device. A remote SSH server can also serve as the execution backend, turning your phone into a mobile workstation for remote projects.

There is nothing to set up beforehand: install the app and configure a model under AI Vendors. Container images are added or downloaded manually the first time. AiGo ships no models and locks you into no vendor: it supports the OpenAI / Anthropic / Gemini protocols plus custom providers, with models, keys and endpoints all configured by you.

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
- **Git Integration** — Visual management of status, branches, commit history, diffs and tags, plus staging/discarding changes, sign-off and credentials
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

The single source of truth for versions is Git — never edit `build.gradle.kts` by hand.

| Field | Source | Rule |
| --- | --- | --- |
| `versionCode` | `git rev-list --count HEAD` | total commit count, +1 per commit |
| `versionName` | `git describe --tags --always --dirty` | see below, build date always appended |

Three shapes of `versionName`:

| State of current commit | versionName | Example |
| --- | --- | --- |
| Exactly on a tag | `<tag>+<buildDate>` | `1.0.0+20261121` |
| N commits past a tag | `<tag>-dev.N+<hash>.<buildDate>` | `1.0.0-dev.9+350da83.20261121` |
| No tag at all | `1.0.0-dev+<hash>.<buildDate>` | `1.0.0-dev+350da83.20261121` |

- **The build date comes from the git commit date**, not the machine clock — the same commit
  always yields the same version, on CI and locally.
- The date sits after `+` (SemVer build metadata) so it **does not participate in comparison**
  and cannot break update detection. Putting it after `-` would make the build older than the
  matching release.
- If the tag already contains an 8-digit date (e.g. `v1.0.0-20261121`), no date is appended.

Pin the version for local rebuilds:

```bash
sh gradlew :app:assembleUniversalDebug \
  -PversionCodeOverride=14 -PversionNameOverride=1.0.0-dev
```

**Never rebase / squash / force-push `main`** — `versionCode` derives from the commit count,
so rewriting history makes it go backwards and new builds stop installing.

## Release

This repository is a fork of Nox, and **GitHub does not trigger workflows on tag pushes to
forked repositories**. Releases are therefore started manually; CI creates the tag and the
GitHub Release itself.

Trigger from the Actions page (or via API) with `release_tag=v1.0.0` and one of:

| `flavors` | Artifacts | Use case |
| --- | --- | --- |
| `all` | universal + arm64 + x86_64 | public release |
| `arm64` | arm64 only | your own device |
| `x86_64` | x86_64 only | emulators |

Run `aigo release-check` first to clear stray temporary tags, otherwise `git describe` is
polluted and `versionName` comes out as `manual-2-6-gxxxx`.

Artifacts are named `AiGo-<abi>-<versionName>-<summary>.apk`. The **summary is ASCII-only**:
the GitHub Release Assets API rewrites non-ASCII characters to `-`, so the human-readable
notes live in the first line of the release body instead.

## Star

If AiGo is helpful to you, give it a [Star](https://github.com/steveneng2020/AiGo).


## Feedback & Contribution

- **Contact**: steveneng@163.com
- **Bug reports**: open an [Issue](https://github.com/steveneng2020/AiGo/issues) with reproduction steps, device model and OS version
- **Feature requests**: discuss your ideas in [Issues](https://github.com/steveneng2020/AiGo/issues)
- **Contributing**: submit a [Pull Request](https://github.com/steveneng2020/AiGo/pulls)

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
