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


## Plans and architecture.

Plan-mode scratch files are written into `docs/plans/` (via `plansDirectory` in
`.claude/settings.json`), named after the session slug, so every plan lands in the repo instead of
`~/.claude/plans/`. That file is the raw capture; before calling ExitPlanMode, route the plan to a
durable home:

- **Architectural decision** — a new subsystem, a state model, a choice later work must respect:
  write an ADR at `docs/adr/NNNN-short-title.md` using the next available number.
- **Big refactor or multi-phase project**: keep the plan in `docs/plans/`, and once you have exited
  plan mode rename the scratch file to `YYYY-MM-DD-short-title.md` (renaming it during plan mode
  breaks plan-mode re-entry, which reads the file by its original path).
- **Small bugfix or anything short-lived**: put the plan in the GitHub issue instead, then delete the
  scratch file. It should not sit in the repo forever.

When the routing is genuinely unclear, ask rather than defaulting to an ADR.
