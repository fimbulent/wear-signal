# Bumping the vendored Signal-Android modules

Reference notes for updating the vendored Signal code (`lib/libsignal-service`,
`core/util-jvm`, `core/models-jvm`, `core/network`) and the binary deps that must move
in lockstep with them (`libsignal-client`, `ringrtc-android`). Written during the
v8.15.0 → v8.24.1 bump (2026-08-20); update the "Last bump" section at the bottom each
time.

## The vendoring model (what "vendored" means here)

The four modules are byte-for-byte copies of upstream
[Signal-Android](https://github.com/signalapp/Signal-Android) at a pinned tag —
**same paths as upstream** (`lib/libsignal-service`, `core/*`) — with exactly these
deviations:

1. **Each module's `build.gradle.kts` is ours, not upstream's.** Trimmed for
   standalone use: publishing/signing/ktlint/idea plugins removed, test + testFixtures
   deps removed, `jvmToolchain` replaced with per-task `jvmTarget` compilerOptions.
   Each carries a header comment
   "Vendored from Signal-Android vX.Y.Z; build file trimmed for standalone use."
   **Do not overwrite these with upstream's** — instead diff upstream's old vs new
   build script and port over only dependency changes (see step 4 below).
   Since the 8.24.1 bump, util-jvm has the wire plugin + `wire {}` block (upstream
   moved `DeviceName.proto` into it); if a module's compile fails with unresolved
   proto-generated classes, check whether upstream added protos to a module whose
   trimmed build file lacks the wire plugin.
2. **`src/test/` and `src/testFixtures/` are pruned** (only `src/main` is vendored).
3. **`PushServiceSocket.java` carries a local patch**: two added methods,
   `waitForTransferArchive(...)` (link+sync transfer archive long-poll) and
   `retrieveGroupsV2ProfileAvatar(...)`. Reapply after copying upstream sources.
4. **`lib/libsignal-service/src/main/protowire/Backup.proto` is imported from
   upstream's `lib/archive/src/main/protowire/Backup.proto`** — a module we don't
   vendor. The fork drops the proto into libsignal-service's protowire dir so the
   wire plugin generates `org.signal.archive.proto.*` classes (used by the
   transfer-archive/history import). Refresh it from the new tag's `lib/archive`.
5. **`wire-handler/` contains only a prebuilt `wire-handler-1.0.0.jar`** (upstream
   builds this tiny wire schema-handler plugin from source; it never changes —
   unchanged 8.15→8.24). Referenced from the libsignal-service wire config.

## Bump procedure

1. **Pick the target tag.** `git ls-remote --tags https://github.com/signalapp/Signal-Android.git 'v8.*'`
   and take the latest stable. Shallow-clone BOTH the currently-vendored tag and the
   target tag into scratch space:
   `git clone --depth 1 --branch vX.Y.Z https://github.com/signalapp/Signal-Android.git sa-X.Y.Z`
   Having both lets you (a) verify what the local deviations are (diff ours vs old
   upstream — should reproduce exactly the list above and nothing else) and (b) see
   what upstream changed (diff old vs new).

2. **Verify the deviation list is still accurate**:
   `diff -rq --exclude=build --exclude=.gradle sa-OLD/<module> <repo>/<module>`
   for each module. If new deviations appear (someone patched vendored code since the
   last bump), note them and plan to reapply. `git diff <vendor-commit>..HEAD -- lib/ core/`
   also shows accumulated local patches.

3. **Copy sources**: for each module, delete `<repo>/<module>/src/main` and copy
   `sa-NEW/<module>/src/main` in its place. Keep our `build.gradle.kts`.
   Then reapply the local patches (PushServiceSocket methods, Backup.proto from
   `sa-NEW/lib/archive/src/main/protowire/Backup.proto`).

4. **Port build-script dependency changes**: `diff sa-OLD/<module>/build.gradle.kts
   sa-NEW/<module>/build.gradle.kts` — port only `dependencies {}` / `wire {}` changes
   into our trimmed files, and bump the version in the header comment.
   (8.15→8.24: the only change was core/network gaining `api(libs.square.okhttp3)`.)

5. **Bump lockstep versions in `gradle/libs.versions.toml`** to what the new tag pins
   in `sa-NEW/gradle/libs.versions.toml`:
   - `libsignal` (upstream key: `libsignal-client`) — MUST match; libsignal-service
     calls libsignal-client JNI APIs that change signatures between releases.
   - `ringrtc` (upstream key: `signal-ringrtc`) — RingRTC and libsignal versions are
     coupled via WebRTC/CallManager.
   - Compare `square-okhttp3`, `square-wire-runtime`, `square-okio`, `jackson-*`,
     `rxjava3-*` too; keep in sync when upstream moved.

6. **Clean stale generated code** (wire output lives under `<module>/build/generated`):
   `./gradlew clean` (or at least `:lib:libsignal-service:clean :core:util-jvm:clean :core:network:clean`).

7. **Build and fix app-code drift**: `JAVA_HOME=<see memory: building-on-nixos> ./gradlew :app:assembleDebug`.
   Errors will be in `app/` (and any `dev.sam.wearsignal` code) calling moved/renamed
   library APIs. Fix mechanically; consult how upstream's `app/` at the new tag calls
   the same API when the new shape isn't obvious —
   `grep -rn <symbol> sa-NEW/app/src/main` is the fastest way to see intended usage.

8. **Update the README's "Built against ... vX.Y.Z" line** and the version in each
   build-file header comment; update "Last bump" below; commit.

## Last bump: v8.15.0 → v8.24.1 (2026-08-20)

Lockstep versions: libsignal 0.94.4 → 0.100.0, ringrtc 2.69.3 → 2.71.0,
wire 6.4.0 → 6.4.5 (both the root-buildscript plugin and `square-wire-runtime`),
`javaVersion`/`kotlinJvmTarget` 17 → 21 (libsignal-client 0.100.0 publishes
class files requiring JVM 21; Gradle refuses to resolve it for a 17 toolchain).
Source churn: 77 files in libsignal-service, 6 in util-jvm, 5 in network, 0 in
models-jvm. The PushServiceSocket patch applied cleanly via `git apply`.
Every app-side fix below was found by compiling and then reading how upstream's
`app/` calls the same API at the new tag — that recipe answered every question.

API drift fixed in app code (~60 compile errors, 8 files, all mechanical):

- **Config classes moved packages**: `org.whispersystems.signalservice.internal.configuration.*`
  and `api.push.TrustStore` → `org.signal.network.config.*` (constructors unchanged;
  pure import rewrite in SignalNet.kt).
- **`EnvelopeResponse` became a sealed class** (`Parsed` / `Unparseable`). Batch-drain
  loops must gate `processor.process(...)` behind `is EnvelopeResponse.Parsed` and
  still `sendAck()` every response (MessageRetriever.kt, CallSignaling.kt).
- **RingRTC `CallManager.proceed()`**: gained a `VideoConfig` param after `AudioConfig`,
  lost the `enableVp9: Boolean` param, gained trailing `statsIntervalSecs: Int?`
  (CallEngine.kt; default `VideoConfig()` + `null` stats preserved old behavior).
- **Secondary-device registration**: `registerAsSecondaryDevice` now takes a slim
  `DeviceAttributes(fetchesMessages, registrationId, pniRegistrationId, name,
  capabilities)` instead of full `AccountAttributes`; `Capabilities` gained
  `optionalPhoneNumber` (upstream passes `false`) (LinkingRepository.kt).
- **`ProvisioningSocket.Mode`**: enum → sealed class; `Mode.LINK` (which always sent
  `&capabilities=backup5`) → `Mode.Link(linkAndSyncCapable = true)` (LinkingViewModel.kt).
- **`SignalServiceDataStore`** gained `pniOrNull()`; **`SignalServiceAccountDataStore`**
  gained `setMultiDevice(Boolean)` (WatchDataStore.kt, WatchProtocolStore.kt — the
  watch answers `pniOrNull() = pni store`, `setMultiDevice = no-op`).
- **libsignal `SessionRecord.hasSenderChain(Double)` → `hasSenderChain()`** (no-arg;
  matches upstream TextSecureSessionStore) (WatchProtocolStore.kt).
- **`HealthMonitor`** gained `onServerTimestamp(Long, Boolean)` — no-op override
  (SignalNet.kt).
- **`PreKeyRepository`** constructor gained a `sessionLock: SignalSessionLock` param
  before `batchHelper` (SignalNet.kt).
- Upstream deleted `api/archive/*` response DTOs from libsignal-service — the app
  didn't use them, no action needed.

Verified on hardware after this bump: message send/receive on the existing link
(2026-08-20, release build on a Pixel Watch 4). Not re-verified: fresh linking
(QR + transfer archive) and a RingRTC call. Compile-clean ≠ protocol-correct;
exercise the risky paths on a real watch before trusting a bump.
