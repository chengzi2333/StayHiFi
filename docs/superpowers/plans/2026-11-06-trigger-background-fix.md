# StayHiFi v1.2.4 Trigger and Background Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every automatic-apply gate diagnosable, prevent notification refreshes from amplifying a blocked carrier call, and stop candidate execution from trusting a stale pre-write snapshot.

**Architecture:** Keep the existing per-MAC `TriggerGate`, provider lease ordering, and listener lifecycle. Add structured decision results at the existing gates and a small single-flight refresh gate; do not pretend coroutine timeout cancels Binder. Fix candidate short-circuiting only after the first attempted write, without changing targets, defaults, or product tiers.

**Tech Stack:** Kotlin, coroutines, Android Service/BroadcastReceiver, JUnit4, kotlinx-coroutines-test.

**Spec:** `docs/dev/2026-11-06-v1.2.4-orchestration.md` (router-owned source of truth); root-cause report sent to router on 2026-11-06.

## Global Constraints

- Base is `e0e0ee1`; branch is `fix/1.2.4-trigger-background`; work only in this worktree.
- Production changes are limited to `trigger/`; no `strings.xml`, `SettingsRepository`, Gradle files, version numbers, release packaging, or keystore changes.
- Root background operation remains non-interactive; no shell creation, authorization request, or silent provider fallback.
- Do not change LHDC/LDAC targets, defaults, or add quality tiers before the user rules through router.
- Do not claim an 8-second coroutine timeout terminates a blocked Binder transaction.
- Local verification is JVM + lint + minified build only; physical playback, heat, memory, and ROM delivery require external validation.

## Review Focus

- An active trigger and a completed-window rejection must produce different, factual log reasons; pinned by Task 1 dispatcher tests.
- `force=true` must still not bypass an active same-MAC run; pinned by existing and updated Task 1 tests.
- A second refresh arriving while a carrier call is physically blocked must return without another IPC and without posting a fallback notification; pinned by Task 2 blocking-fake test.
- After any candidate write was attempted, a later candidate matching the original snapshot must still be written and verified; pinned by Task 3 regression test.
- Cold Root rejection must remain visible without triggering interactive authorization; pinned by existing `BackgroundProviderContractTest` plus Task 1 provider-gate log test.

---

### Task 1: Trigger Decision Evidence

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/TriggerGate.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/TriggerDispatcher.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyProviderGate.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyConnectionGuard.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ListenerService.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/TriggerGateTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/TriggerDispatcherTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/ApplyProviderGateTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/ApplyConnectionGuardTest.kt`

**Interfaces:**
- Consumes: existing `TriggerRunner`, `ProviderCoordinator.acquire`, and `LocalA2dpProbe` contracts.
- Produces: `TriggerGate.acquire(mac: String, force: Boolean = false): TriggerGate.Acquisition`, whose result is `Granted(permit)` or `Rejected(reason, elapsedMillis?, remainingMillis?)`; existing `tryAcquire` remains as a compatibility delegate. `ApplyProviderGate(lock: Mutex = ApplyLock.mutex, log: (String) -> Unit = {})` preserves existing callers.

- [ ] **Step 1: Write failing decision and outcome tests**

Add tests proving: active and completed-window rejection logs contain distinct reasons; granted dispatch logs source/MAC/force and runner return is not described as apply success; runner exception is logged and rethrown; connected/unknown/timeout guard outcomes identify the state; provider-gate acquire success logs owner/source/kind/generation and `ProviderAccessException` logs its exact reason.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `/usr/bin/env JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/chengzi/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests 'com.chengzi.stayhifi.trigger.Trigger*' --tests 'com.chengzi.stayhifi.trigger.ApplyProviderGateTest' --tests 'com.chengzi.stayhifi.trigger.ApplyConnectionGuardTest'`
Expected: compilation or assertion failure because detailed decisions/logs do not yet exist.

- [ ] **Step 3: Implement atomic decisions and stage logs**

Implement `TriggerGate.Acquisition` in the same synchronized critical section as permit creation; never infer rejection through a second racy query. `TriggerDispatcher` logs permit grant, exact rejection, runner return, exception, and permit release. `ApplyProviderGate` logs lock acquisition, lease grant/close, and rethrows acquisition failures after logging. `ApplyConnectionGuard` logs the observed state and final branch without treating UNKNOWN as connected. `ListenerService` records ACL arrival before any notification I/O and performs the privileged notification refresh after dispatch, so a stuck read cannot hide or indefinitely delay the trigger; event bumps remain. No log says a returned runner proves audio or write success.

- [ ] **Step 4: Run focused and background contract tests**

Run the Step 2 command plus `--tests 'com.chengzi.stayhifi.trigger.BackgroundProviderContractTest'`.
Expected: PASS; cold Root still performs zero shell/bind and existing permit/lock ordering remains intact.

- [ ] **Step 5: Commit**

Commit only Task 1 source/tests with message `Fix: 补全自动拉满触发决策证据` and the required Claude Code attribution.

### Task 2: Single-Flight Notification Refresh

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/trigger/NotificationRefreshGate.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ListenerService.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/NotificationRefreshGateTest.kt`

**Interfaces:**
- Consumes: `ListenerService.refreshNotification()` and its existing `notifyPersistent` check.
- Produces: `NotificationRefreshGate.run(block: suspend () -> Unit): Boolean`; returns false without invoking `block` when another refresh is active, otherwise executes exactly once and releases in `finally`.

- [ ] **Step 1: Write failing single-flight and blocking-call tests**

Use coroutines-test and latches to prove concurrent refreshes execute once, a non-cooperative blocked first call causes later calls to return false immediately, exceptions release the gate, and a later call runs after physical completion. Include the service-level invariant in comments/assertions: a skipped refresh returns before notification fallback content is computed or posted.

- [ ] **Step 2: Run the new test and verify RED**

Run: `/usr/bin/env JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/chengzi/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests 'com.chengzi.stayhifi.trigger.NotificationRefreshGateTest'`
Expected: RED because the gate does not exist.

- [ ] **Step 3: Implement non-blocking merge at the whole-refresh boundary**

Use `Mutex.tryLock`; do not queue behind a physically stuck Binder call. Wrap the complete privileged read + notification post, returning immediately on merge so `TEXT_WAITING` is never posted by a skipped refresh. Keep the 30-second interval, setting default, drift checks, local disconnect detection, and FGS lifecycle unchanged. Keep timeout logs factual: they only bound cooperative waiting and cannot prove physical IPC termination.

- [ ] **Step 4: Run gate and listener-adjacent tests**

Run the new test plus `BackgroundProviderContractTest` and `ApplyConnectionGuardTest`.
Expected: PASS; no extra carrier calls are introduced.

- [ ] **Step 5: Commit**

Commit only Task 2 source/tests with message `Fix: 合并常驻通知并发刷新` and the required Claude Code attribution.

### Task 3: Post-Write Candidate Freshness

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyCandidateRunner.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/ApplyCandidateRunnerTest.kt`

**Interfaces:**
- Consumes: existing `ApplyPolicy.matches`, `TargetAssessment`, and `writeVerified` callback.
- Produces: unchanged public API; `AlreadyAligned` is allowed only before the first write attempt.

- [ ] **Step 1: Write the stale-snapshot regression test**

Use `current=48kHz`, candidates `[192kHz, 48kHz]`, first `writeVerified=false`, second `true`. Assert both writes occur and result is `Applied(48kHz)`, not `AlreadyAligned`; also assert a no-write aligned first candidate still returns `AlreadyAligned` without calling `beforeFirstWrite`.

- [ ] **Step 2: Run the candidate test and verify RED**

Run: `/usr/bin/env JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/chengzi/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests 'com.chengzi.stayhifi.trigger.ApplyCandidateRunnerTest'`
Expected: the new test fails because the second candidate is incorrectly short-circuited against the original snapshot.

- [ ] **Step 3: Implement the one-condition fix**

Only perform the initial `matches(candidate, current)` shortcut before any write attempt. Once a write was attempted, every later candidate must go through real write/readback. Do not add a new rollback policy or alter candidates.

- [ ] **Step 4: Run candidate and policy tests**

Run `ApplyCandidateRunnerTest`, `ApplyPolicyTest`, and `PreferredApplyRunnerTest`.
Expected: PASS with unchanged target derivation and preferences.

- [ ] **Step 5: Commit**

Commit only Task 3 source/tests with message `Fix: 写入后不再信过期配置快照` and the required Claude Code attribution.

### Task 5: LHDC Quality Descent and Explicit-Failure Recovery

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyPolicy.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyCandidateRunner.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyConnectionGuard.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/ApplyPolicyTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/ApplyCandidateRunnerTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/ApplyConnectionGuardTest.kt`

**Interfaces:**
- Consumes: Task 3 freshness rule and the router-relayed user decision on 2026-11-06.
- Produces: non-vivo fixed-tier LHDC candidates try cs1 `0x8008, 0x8007, …, 0x8000` at the proven highest rate/bits before the original-cs1 rate fallback, with duplicates removed; every candidate in that chain requires exact cs1 readback. `ApplyCandidateRunner.run` gains optional `restoreOriginOnce: (suspend () -> Boolean)? = null` and terminal `Recovered`/`RecoveryFailed` results. `ApplyConnectionGuard.currentState(mac)` exposes the existing probe for terminal-failure reason logging.

- [ ] **Step 1: Write failing quality-chain and recovery tests**

Pin the complete 1000→900→500→400→320→256→192→128→64 sequence, strict readback for every tier, unchanged adaptive behavior, and no duplicate original-quality/rate candidate. Drive 1000/900/500 rejection then 400 success to prove Task 3 never uses stale current after multiple writes. For terminal exhaustion, prove the origin callback is invoked exactly once and maps to `Recovered` or `RecoveryFailed`; success never invokes recovery. Pin explicit DISCONNECTED versus UNKNOWN state reporting.

- [ ] **Step 2: Run policy/candidate/guard tests and verify RED**

Run all three test classes.
Expected: current chain jumps from 1000 to original cs1, only 1000 is strict, and recovery API/results do not exist.

- [ ] **Step 3: Implement complete descent and one terminal recovery attempt**

Generate the quality sequence from the existing proven target without changing 1000-first or adaptive behavior. Generalize exact cs1 verification to every candidate in this raised-LHDC chain. Invoke origin recovery only after all candidates fail; intermediate rejection continues descent. ApplyWorker logs local DISCONNECTED when confirmed, otherwise READBACK_FAILED/UNKNOWN, then writes the pre-write config once with strict final/readback matching and no outer write retry. Record the restored drift baseline only on success. Logs/comments must state that recovery causes one renegotiation and cannot detect silent/noisy playback. A disconnect discovered only after a successful transaction is not reclassified as proof of failure.

- [ ] **Step 4: Run Task 5 and regression tests**

Run `ApplyPolicyTest`, `ApplyCandidateRunnerTest`, `ApplyConnectionGuardTest`, `ApplyWorkerManualTest`, and `BackgroundProviderContractTest`.
Expected: PASS; manual behavior, adaptive semantics, and provider interaction remain unchanged.

- [ ] **Step 5: Commit**

Commit only Task 5 source/tests with message `Fix: 补齐 LHDC 逐级探测与失败恢复` and the required Claude Code attribution.

### Task 4: Verification and Handoff

- [ ] **Step 1: Review the complete diff and boundaries**

Confirm no shared/cross-domain production file, default, target, version, manifest, or packaging change. Check all new logs use factual stage/outcome wording.

- [ ] **Step 2: Run full local verification**

Run: `/usr/bin/env JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/chengzi/Library/Android/sdk ./gradlew testDebugUnitTest lint`
Then run the repository's existing minified release build task without publishing or renaming an APK.
Expected: all commands exit 0; record exact task names and results.

- [ ] **Step 3: Report evidence and external limits to router**

Send commit SHAs, tests/build results, remaining Binder/ROM limitations, and the unresolved #6 A/B/C product decision. Do not claim physical playback, heat, memory, or automatic triggering is externally verified.
