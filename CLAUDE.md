# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working in this repository.
Read [`AGENTS.md`](AGENTS.md) first, then [`STYLE.md`](STYLE.md) and any focused
skill that AGENTS.md routes to for the task. Only Claude Code–specific additions
appear below.

## Claude Code notes

- For code navigation and file discovery, do not use Bash find/grep/ls pipelines.

    Use:
        - Glob for file discovery
        - grep or rg for text search
        - LSP for definitions, references, symbols, hover/type info, and diagnostics
        - check for ripgrep and fd as desired, I usually have them.

    Only use Bash for commands that genuinely need shell execution, such as builds, tests, git, or project scripts.
### Accessibility: contentDescription

See [Compose accessibility in `STYLE.md`](STYLE.md#compose-accessibility) — do
not duplicate that guidance here.


## Plan-mode scratch files

Plan-mode scratch files are written to `docs/plans/` (via `plansDirectory` in
`.claude/settings.json`). Follow the plan-retention policy in `AGENTS.md`
before exiting plan mode. If retaining a plan in `docs/plans/`, rename its
scratch file to `YYYY-MM-DD-short-title.md` only after exiting plan mode;
plan-mode re-entry uses the original path.
