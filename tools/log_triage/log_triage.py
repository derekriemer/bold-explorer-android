#!/usr/bin/env python3
"""Local-LLM triage pipeline for Bold Explorer audio_log.jsonl session logs.

Log format: one JSON object per line (see AGENTS.md "Audio log format").

Subcommands:
  find    - list line numbers of "important" events (default: kind=USER_MARKER)
  triage  - find + per-event map-summarize (local model) + reduce into overview.md/bugs.csv
  retry   - recompute one cached event's summary, e.g. with --backend claude
  full    - whole-file pass with no event windowing (chunked map-reduce for large files)

Backends are pluggable per-call: default is a local Ollama model (cheap, private,
good enough for "what does this window of entries look like"), with a Claude Code
CLI backend available for the reduce step or for any individual event a small
model handled poorly (`retry <id> --backend claude`).
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path

DEFAULT_MODEL = "gemma3"
DEFAULT_WINDOW = 30
DEFAULT_OLLAMA_HOST = "http://localhost:11434"

MAP_EVENT_PROMPT = """You are triaging a session log for a blind user's GPS trail-following \
navigation app. Below is a window of log entries leading up to and including one flagged \
EVENT (marked "<== EVENT"). Each row is: line_no, time-offset-from-event, kind, trigger, \
inputs, outputs, played, note.

{table}

In 3-5 sentences, explain what appears to have gone wrong (if anything) around the EVENT \
line, citing specific field values. If nothing looks wrong, say so plainly. Do not speculate \
beyond what the fields show."""

MAP_CHUNK_PROMPT = """You are triaging a session log for a blind user's GPS trail-following \
navigation app. Below is a chunk of consecutive log entries (line_no, time-offset-from-chunk-start, \
kind, trigger, inputs, outputs, played, note).

{table}

In a short paragraph, note anything that looks like a bug, a stuck/bailing detector, an \
unexpected gap, or an unwanted audio outcome. Cite line numbers. If nothing looks wrong, say so \
plainly."""

REDUCE_PROMPT = """You triaged {n} sections of a navigation-app session log. Below are the \
per-section summaries, each tagged with its anchor line number.

{summaries}

Produce two things, separated by the literal marker lines shown below, and nothing else:

===OVERVIEW===
A short markdown doc: what went wrong in this session, grouped by root cause, in priority order.

===BUGS_JSON===
A JSON array, one object per distinct bug, with keys: line, timestamp_ms, severity \
(low/med/high), summary, suspected_cause. Valid JSON only in this section, no markdown fences, \
no trailing commentary."""


@dataclass
class Entry:
    line_no: int
    raw: str
    data: dict


def load_entries(path: Path) -> list[Entry]:
    entries = []
    with path.open() as f:
        for i, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                data = json.loads(line)
            except json.JSONDecodeError:
                continue
            entries.append(Entry(i, line, data))
    return entries


def matches(entry: Entry, kind: str | None, grep: str | None) -> bool:
    if kind and entry.data.get("kind") != kind:
        return False
    if grep and not re.search(grep, entry.raw, re.IGNORECASE):
        return False
    return True


def find_events(entries: list[Entry], kind: str | None, grep: str | None) -> list[Entry]:
    return [e for e in entries if matches(e, kind, grep)]


def window_for(entries: list[Entry], target: Entry, lines: int, seconds: float | None) -> list[Entry]:
    idx = next(i for i, e in enumerate(entries) if e.line_no == target.line_no)
    if seconds is not None:
        cutoff = target.data.get("ts", 0) - seconds * 1000
        start = idx
        while start > 0 and entries[start - 1].data.get("ts", 0) >= cutoff:
            start -= 1
    else:
        start = max(0, idx - lines)
    return entries[start : idx + 1]


def format_table(entries: list[Entry], ref_ts: int, mark_line: int | None = None) -> str:
    rows = []
    for e in entries:
        delta = e.data.get("ts", 0) - ref_ts
        marker = " <== EVENT" if e.line_no == mark_line else ""
        d = e.data
        rows.append(
            f"{e.line_no}\t{delta:+d}ms\t{d.get('kind', '')}\t{d.get('trigger', '')}\t"
            f"in={d.get('inputs', '')}\tout={d.get('outputs', '')}\tplayed={d.get('played', '')}\t"
            f"note={d.get('note', '')}{marker}"
        )
    return "\n".join(rows)


class Backend:
    def summarize(self, prompt: str) -> str:
        raise NotImplementedError


class OllamaBackend(Backend):
    def __init__(self, model: str = DEFAULT_MODEL, host: str = DEFAULT_OLLAMA_HOST):
        self.model = model
        self.host = host

    def summarize(self, prompt: str) -> str:
        body = json.dumps({"model": self.model, "prompt": prompt, "stream": False}).encode()
        req = urllib.request.Request(
            f"{self.host}/api/generate", data=body, headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read())["response"].strip()


class ClaudeBackend(Backend):
    """Shells out to the Claude Code CLI in non-interactive print mode."""

    def summarize(self, prompt: str) -> str:
        result = subprocess.run(
            ["claude", "-p", prompt], capture_output=True, text=True, timeout=180, check=True
        )
        return result.stdout.strip()


def make_backend(name: str, model: str) -> Backend:
    if name == "claude":
        return ClaudeBackend()
    return OllamaBackend(model=model)


def cache_dir_for(logfile: Path) -> Path:
    d = Path(".log_triage_cache") / logfile.stem
    d.mkdir(parents=True, exist_ok=True)
    return d


def summarize_one(entry_id: int, table: str, prompt_template: str, backend: Backend) -> dict:
    prompt = prompt_template.format(table=table)
    summary = backend.summarize(prompt)
    return {"id": entry_id, "summary": summary}


def cmd_find(args):
    entries = load_entries(args.logfile)
    events = find_events(entries, args.kind, args.grep)
    for e in events:
        note = e.data.get("note", "")
        suffix = f"  note={note!r}" if note else ""
        print(f"{e.line_no}\t{e.data.get('kind', '')}\t{e.data.get('trigger', '')}{suffix}")
    print(f"\n{len(events)} matching event(s)", file=sys.stderr)


def cmd_triage(args):
    entries = load_entries(args.logfile)
    cache = cache_dir_for(args.logfile)
    map_backend = make_backend(args.backend, args.model)

    if not args.reduce_only:
        events = find_events(entries, args.kind, args.grep)
        if not events:
            print("No matching events found.", file=sys.stderr)
            return
        pending = []
        for e in events:
            cache_file = cache / f"{e.line_no}.json"
            if cache_file.exists() and not args.force:
                continue
            pending.append(e)
        print(f"{len(events)} events, {len(pending)} to compute (rest cached)", file=sys.stderr)

        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = {}
            for e in pending:
                window = window_for(entries, e, args.window, args.seconds)
                table = format_table(window, e.data.get("ts", 0), mark_line=e.line_no)
                fut = pool.submit(summarize_one, e.line_no, table, MAP_EVENT_PROMPT, map_backend)
                futures[fut] = e.line_no
            for fut in as_completed(futures):
                line_no = futures[fut]
                try:
                    result = fut.result()
                except Exception as exc:
                    print(f"  event {line_no}: FAILED ({exc})", file=sys.stderr)
                    continue
                result["backend"] = args.backend
                (cache / f"{line_no}.json").write_text(json.dumps(result, indent=2))
                print(f"  event {line_no}: done", file=sys.stderr)

    reduce_backend_name = args.reduce_backend or args.backend
    reduce_backend = make_backend(reduce_backend_name, args.model)
    run_reduce(cache, reduce_backend, args.out)


def cmd_retry(args):
    entries = load_entries(args.logfile)
    target = next((e for e in entries if e.line_no == args.id), None)
    if target is None:
        print(f"No log entry at line {args.id}", file=sys.stderr)
        sys.exit(1)
    window = window_for(entries, target, args.window, args.seconds)
    table = format_table(window, target.data.get("ts", 0), mark_line=target.line_no)
    backend = make_backend(args.backend, args.model)
    result = summarize_one(target.line_no, table, MAP_EVENT_PROMPT, backend)
    result["backend"] = args.backend
    cache = cache_dir_for(args.logfile)
    (cache / f"{target.line_no}.json").write_text(json.dumps(result, indent=2))
    print(result["summary"])
    print(f"\nCached. Run `triage --reduce-only` to regenerate overview.md/bugs.csv.", file=sys.stderr)


def run_reduce(cache: Path, backend: Backend, out_dir: Path):
    cached = sorted(cache.glob("*.json"), key=lambda p: int(p.stem))
    if not cached:
        print("No cached summaries to reduce.", file=sys.stderr)
        return
    summaries = []
    for p in cached:
        data = json.loads(p.read_text())
        summaries.append(f"[line {data['id']}]\n{data['summary']}")
    prompt = REDUCE_PROMPT.format(n=len(summaries), summaries="\n\n".join(summaries))
    output = backend.summarize(prompt)
    write_report(output, out_dir)


def write_report(output: str, out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    overview_marker, bugs_marker = "===OVERVIEW===", "===BUGS_JSON==="
    if overview_marker not in output or bugs_marker not in output:
        (out_dir / "triage_raw.txt").write_text(output)
        print("Model output didn't match expected format; wrote triage_raw.txt", file=sys.stderr)
        return

    overview = output.split(overview_marker, 1)[1].split(bugs_marker, 1)[0].strip()
    bugs_json = output.split(bugs_marker, 1)[1].strip()
    (out_dir / "overview.md").write_text(overview + "\n")

    try:
        bugs = json.loads(bugs_json)
    except json.JSONDecodeError:
        (out_dir / "bugs_raw.txt").write_text(bugs_json)
        print("Couldn't parse bugs JSON; wrote bugs_raw.txt instead of bugs.csv", file=sys.stderr)
        return

    fields = ["line", "timestamp_ms", "severity", "summary", "suspected_cause"]
    with (out_dir / "bugs.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for bug in bugs:
            writer.writerow({k: bug.get(k, "") for k in fields})

    print(f"Wrote {out_dir / 'overview.md'} and {out_dir / 'bugs.csv'}", file=sys.stderr)


def cmd_full(args):
    entries = load_entries(args.logfile)
    if not entries:
        print("No entries found.", file=sys.stderr)
        return
    chunks = [entries[i : i + args.chunk_lines] for i in range(0, len(entries), args.chunk_lines)]
    backend = make_backend(args.backend, args.model)
    cache = cache_dir_for(args.logfile) / "full"
    cache.mkdir(parents=True, exist_ok=True)

    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {}
        for chunk in chunks:
            anchor = chunk[0].line_no
            table = format_table(chunk, chunk[0].data.get("ts", 0))
            fut = pool.submit(summarize_one, anchor, table, MAP_CHUNK_PROMPT, backend)
            futures[fut] = anchor
        for fut in as_completed(futures):
            anchor = futures[fut]
            try:
                result = fut.result()
            except Exception as exc:
                print(f"  chunk @{anchor}: FAILED ({exc})", file=sys.stderr)
                continue
            (cache / f"{anchor}.json").write_text(json.dumps(result, indent=2))
            print(f"  chunk @{anchor}: done", file=sys.stderr)

    run_reduce(cache, backend, args.out)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("logfile", type=Path)
    sub = p.add_subparsers(dest="command", required=True)

    f = sub.add_parser("find", help="list line numbers of important events")
    f.add_argument("--kind", default="USER_MARKER")
    f.add_argument("--grep", default=None, help="regex applied to the raw JSON line")
    f.set_defaults(func=cmd_find)

    t = sub.add_parser("triage", help="find + map-summarize + reduce into overview.md/bugs.csv")
    t.add_argument("--kind", default="USER_MARKER")
    t.add_argument("--grep", default=None)
    t.add_argument("--window", type=int, default=DEFAULT_WINDOW)
    t.add_argument("--seconds", type=float, default=None, help="time-based window instead of line count")
    t.add_argument("--backend", choices=["ollama", "claude"], default="ollama")
    t.add_argument("--model", default=DEFAULT_MODEL)
    t.add_argument(
        "--reduce-backend",
        choices=["ollama", "claude"],
        default=None,
        help="defaults to --backend; claude recommended for the reduce step",
    )
    t.add_argument("--workers", type=int, default=4)
    t.add_argument("--force", action="store_true", help="recompute even if cached")
    t.add_argument("--reduce-only", action="store_true", help="skip mapping, just reduce existing cache")
    t.add_argument("--out", type=Path, default=Path("."))
    t.set_defaults(func=cmd_triage)

    r = sub.add_parser("retry", help="recompute one cached event, e.g. with a different backend")
    r.add_argument("id", type=int, help="event line number")
    r.add_argument("--backend", choices=["ollama", "claude"], default="claude")
    r.add_argument("--model", default=DEFAULT_MODEL)
    r.add_argument("--window", type=int, default=DEFAULT_WINDOW)
    r.add_argument("--seconds", type=float, default=None)
    r.set_defaults(func=cmd_retry)

    fl = sub.add_parser("full", help="whole-file pass, no event windowing (chunked for large files)")
    fl.add_argument("--backend", choices=["ollama", "claude"], default="claude")
    fl.add_argument("--model", default=DEFAULT_MODEL)
    fl.add_argument("--chunk-lines", type=int, default=1500)
    fl.add_argument("--out", type=Path, default=Path("."))
    fl.set_defaults(func=cmd_full)

    return p


def main():
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
