# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working in this repository.
All architecture, commands, patterns, and rules are defined in [`AGENTS.md`](AGENTS.md) — YOU MUST read that first.
Only Claude Code–specific additions appear below.

## Claude Code notes

- Use the `jj` skill (`/jujutsu`) when working in this repo — it is a jujutsu repository.
- Prefer `make test-shared` for fast feedback; it runs on the JVM with no device needed.
- `make assemble` requires `ANDROID_HOME` to be set.
- For code navigation and file discovery, do not use Bash find/grep/ls pipelines.

    Use:
        - Glob for file discovery
        - Grep for text search
        - LSP for definitions, references, symbols, hover/type info, and diagnostics

    Only use Bash for commands that genuinely need shell execution, such as builds, tests, git, or project scripts.
### Accessibility: contentDescription

See "Accessibility constraints" in [`AGENTS.md`](AGENTS.md) — do not duplicate that guidance here.
