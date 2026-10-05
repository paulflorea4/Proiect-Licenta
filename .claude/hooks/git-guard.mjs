#!/usr/bin/env node
// PreToolUse hook for the Bash tool (wired up in .claude/settings.json).
// Exit code 2 blocks the command and shows stderr to Claude; any other exit lets it run.
// Mechanical backup for the "Git safety" rules in the root CLAUDE.md. Needs only Node,
// which /frontend already requires.

import { execFileSync } from "node:child_process";
import { resolve } from "node:path";

const PROTECTED = new Set(["main", "master"]);
const GIT_OPTS_WITH_VALUE = new Set(["-C", "-c", "--git-dir", "--work-tree", "--namespace"]);
const ON_MAIN_BLOCKED = ["commit", "cherry-pick", "rebase", "revert", "am"];

// Split a command line into segments of tokens. Quote-aware, so a `;` or `&&` inside a
// quoted commit message is not mistaken for a second command.
function parse(cmd) {
  const segments = [];
  let tokens = [];
  let tok = "";
  let has = false;
  let quote = null;
  const endTok = () => { if (has) tokens.push(tok); tok = ""; has = false; };
  const endSeg = () => { endTok(); if (tokens.length) segments.push(tokens); tokens = []; };
  for (let i = 0; i < cmd.length; i++) {
    const c = cmd[i];
    if (quote) {
      if (c === quote) quote = null;
      else if (c === "\\" && quote === '"' && i + 1 < cmd.length) tok += cmd[++i];
      else tok += c;
      continue;
    }
    if (c === "'" || c === '"') { quote = c; has = true; continue; }
    if (c === "\\" && i + 1 < cmd.length) { tok += cmd[++i]; has = true; continue; }
    if (c === "\n" || c === ";" || c === "|" || c === "&") { endSeg(); continue; }
    if (/\s/.test(c)) { endTok(); continue; }
    tok += c;
    has = true;
  }
  endSeg();
  return segments;
}

function git(cwd, args) {
  try {
    return execFileSync("git", ["-C", cwd, ...args], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  } catch {
    return null;
  }
}

const currentBranch = (cwd) => git(cwd, ["rev-parse", "--abbrev-ref", "HEAD"]);
const isLocalBranch = (cwd, name) =>
  git(cwd, ["rev-parse", "--verify", "--quiet", `refs/heads/${name}`]) !== null;

function checkGh(rest) {
  const [a, b] = rest;
  if (a === "pr" && b === "merge") {
    return "never merge a PR yourself - merging is always the human's call (CLAUDE.md, Gates).";
  }
  if (a === "api" && rest.some((x) => /\/pulls\/\d+\/merge\b/.test(x))) {
    return "never merge a PR yourself, not even through `gh api` - merging is the human's call.";
  }
  if (a === "pr" && b === "review") {
    return "never add reviews or approvals on a PR - the human writes review comments. " +
      "Reply to their comments with `gh pr comment` or a fix commit; put your own observations in docs/suggestions.md.";
  }
  return null;
}

function check(cmd, cwd, depth = 0) {
  let assumed = null; // branch this same command line switches to, if any
  for (let seg of parse(cmd)) {
    while (seg.length && /^[A-Za-z_][A-Za-z0-9_]*=/.test(seg[0])) seg = seg.slice(1);
    const [prog, ...rest] = seg;
    if (!prog) continue;

    if (["bash", "sh", "zsh"].includes(prog) && depth < 3) {
      const i = rest.indexOf("-c");
      if (i >= 0 && rest[i + 1]) {
        const r = check(rest[i + 1], cwd, depth + 1);
        if (r) return r;
      }
      continue;
    }
    if (prog === "gh") {
      const r = checkGh(rest);
      if (r) return r;
      continue;
    }
    if (prog !== "git") continue;

    // Skip git's global options (`git -C dir push ...`).
    let dir = cwd;
    let i = 0;
    while (i < rest.length && rest[i].startsWith("-")) {
      if (rest[i] === "-C" && rest[i + 1]) dir = resolve(dir, rest[i + 1]);
      i += GIT_OPTS_WITH_VALUE.has(rest[i]) ? 2 : 1;
    }
    const sub = rest[i];
    const args = rest.slice(i + 1);
    const flags = args.filter((a) => a.startsWith("-"));
    const positional = args.filter((a) => !a.startsWith("-"));
    const branch = () => assumed ?? currentBranch(dir);
    const onProtected = () => PROTECTED.has(branch());

    if (sub === "checkout" || sub === "switch") {
      const creating = flags.some((f) => ["-b", "-B", "-c", "-C", "--create", "--force-create"].includes(f));
      if (creating && positional[0]) assumed = positional[0];
      else if (!flags.includes("--") && positional.length === 1 && isLocalBranch(dir, positional[0])) {
        assumed = positional[0];
      }
      continue;
    }

    if (sub === "reset" && flags.includes("--hard")) {
      return "`git reset --hard` discards work irreversibly. Ask the human first.";
    }

    if (sub === "push") {
      const force =
        flags.some((f) => /^--force(-with-lease|-if-includes)?(=|$)/.test(f) || /^-[A-Za-z]*f[A-Za-z]*$/.test(f)) ||
        args.some((a) => a.startsWith("+"));
      if (force) {
        return "force-pushing is not allowed. If history really must be rewritten, ask the human.";
      }
      if (flags.includes("--mirror") || flags.includes("--all")) {
        return "`git push --all/--mirror` would also push main. Push the single task branch by name.";
      }
      for (const a of positional) {
        const dst = (a.includes(":") ? a.split(":").pop() : a).replace(/^refs\/heads\//, "");
        if (PROTECTED.has(dst)) {
          return "never push to main/master. Push the task branch (phase-<NN>/<id>-<slug>) and open a PR; the human merges.";
        }
      }
      if (onProtected()) {
        return `you are on '${branch()}'. Never push from main/master - switch to the task branch first.`;
      }
      continue;
    }

    if (ON_MAIN_BLOCKED.includes(sub) || (sub === "merge" && !flags.includes("--ff-only"))) {
      if (onProtected()) {
        return `you are on '${branch()}'. Never commit or merge on main/master - create the task branch ` +
          "(phase-<NN>/<id>-<slug>) first. Only `git merge --ff-only` is allowed here, to sync after a PR was merged.";
      }
    }
  }
  return null;
}

let raw = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (c) => (raw += c));
process.stdin.on("end", () => {
  let input;
  try {
    input = JSON.parse(raw);
  } catch {
    process.exit(0); // never brick the session on malformed input
  }
  const cmd = input?.tool_input?.command;
  if (typeof cmd !== "string" || !/\b(git|gh)\b/.test(cmd)) process.exit(0);
  const reason = check(cmd, input.cwd || process.cwd());
  if (reason) {
    console.error(`BLOCKED by .claude/hooks/git-guard.mjs: ${reason}`);
    process.exit(2);
  }
  process.exit(0);
});
