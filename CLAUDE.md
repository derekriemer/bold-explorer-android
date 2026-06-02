# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working in this repository.
All architecture, commands, patterns, and rules are defined in [`AGENTS.md`](AGENTS.md) — read that first.
Only Claude Code–specific additions appear below.

## Claude Code notes

- Use the `jj` skill (`/jujutsu`) when working in this repo — it is a jujutsu repository.
- Prefer `make test-shared` for fast feedback; it runs on the JVM with no device needed.
- `make assemble` requires `ANDROID_HOME` to be set.
