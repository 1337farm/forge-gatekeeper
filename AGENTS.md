# AGENTS.md — nanogatekeeper contributor workflow

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
merge commit and downloads the `NanoGatekeeper-Demo-APK` artifact into
`./apk-out` for device sideload, so every green PR ends with a phone-ready
APK on disk. Use `--latest-apk` instead only when you want the republished
`latest`-release APK.

## Before committing: verify the build
- Unit tests (no device needed):
  `./gradlew :nanogatekeeper:testDebugUnitTest :app:testDebugUnitTest --stacktrace`
- Smoke test (unit tests + AAR assembly + archive checks):
  `bash scripts/smoke-test.sh`
- Full AAR + demo APK:
  `./gradlew :nanogatekeeper:assembleRelease :app:assembleDebug --stacktrace`
