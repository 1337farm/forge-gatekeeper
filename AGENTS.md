# AGENTS.md — gatekeeper contributor workflow

## Before starting ANY task: sync with latest `main`
PR branches go stale fast (automerge squashes into `main` constantly).
A branch cut from an old `main` will be CONFLICTING by the time you push.

1. `git fetch origin`
2. `git checkout main && git reset --hard origin/main`
3. Create the task branch FROM the fresh `main`:
   `git checkout -b <type>/<short-name>`
4. Before pushing / opening a PR, rebase once more:
   `git fetch origin && git rebase origin/main`
   - If commits were already merged upstream, `git rebase --skip` the
     duplicates (check `git log --oneline origin/main` first).
   - Never force-push someone else's branch; use `--force-with-lease`
     only on your own task branches.

## After opening a PR: babysit it to green
Always watch the PR with `bash ~/bin/babysit-pr.sh <PR> [interval] [max_polls] --apk[=dir]`
(shared tool: 1337farm/pr-babysitter v1; repo values in `.babysitrc`)
and hold the turn until it is done: keep polling, and fix every follow-up
failure the script reports instead of stopping at the first red check
(exit 1 = a check failed: read the run log, fix, push, re-run the script).
`--apk` is mandatory: after the merge it waits for main's rebuild on the
merge commit and downloads the `ForgeGatekeeper-Demo-APK` artifact into
`./apk-out` for device sideload, so every green PR ends with a phone-ready
APK on disk. Use `--latest-apk` instead only when you want the republished
`latest`-release APK.

## Before committing: verify the build
- Unit tests (no device needed):
  `./gradlew :gatekeeper:testDebugUnitTest :gatekeeper-litert:testDebugUnitTest :gatekeeper-ort:testDebugUnitTest :app:testDebugUnitTest --stacktrace`
- Smoke test (unit tests + AAR assembly + archive checks):
  `bash scripts/smoke-test.sh`
- Full AARs + demo APK (needs NDK 27 + CMake 3.22.1 for `:gatekeeper-ort`):
  `./gradlew :gatekeeper:assembleRelease :gatekeeper-litert:assembleRelease :gatekeeper-ort:assembleRelease :app:assembleRelease --stacktrace`
- Demo APK only (no split delivery):
  `./gradlew :app:assembleRelease --stacktrace`

## Monolith demo app
The demo APK links both local backends directly (`:gatekeeper-ort`,
`:gatekeeper-litert`) with their native libraries bundled — no runtime
modules, no `DexClassLoader`, no DFM chunks. On demand the app downloads
only the (multi-GB) model in `DownloadService` (foreground,
background-safe).

## Hard rule: every change goes through a PR
- **Never push directly to `main`** (it is protected). Every change,
  no matter how small, must land as a PR: branch → commit → push →
  open PR → babysit to green → automerge. A direct push is a policy
  violation and will be rejected.
- Even a one-line fix gets its own PR and its own babysit run so the
  `latest` release rebuilds with the new APK on disk.
- Reuse the same workflow: `git fetch origin && git checkout main &&
  git reset --hard origin/main && git checkout -b <type>/<short-name>`,
  commit, push, `gh pr create`, then `bash
  /data/data/com.termux/files/home/bin/babysit-pr.sh <PR> --apk`.

## Environment notes
- `gh` and `git` need `export PATH="/data/data/com.termux/files/usr/bin:$PATH"`.
- `git` needs `export HOME=/data/data/com.termux/files/home` (no HOME =
  push prompts for a username even though `gh auth` is configured).
- `gh` needs `export TMPDIR=/data/data/com.termux/files/home/.cache/gh-tmp`.
- `bash ~/bin/babysit-pr.sh` does not work in this shell; use the
  absolute path `/data/data/com.termux/files/home/bin/babysit-pr.sh`.
