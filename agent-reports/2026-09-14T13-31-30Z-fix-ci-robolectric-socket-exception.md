# Process Report: Fix CI Robolectric SocketException on SkylarCorePhase3Test

**Task Timestamp (UTC):** `2026-09-14T13:31:30Z`  
**Task Identifier:** `fix-ci-robolectric-socket-exception`

---

## 1. What Was Asked

The user provided the failure output from the GitHub Actions CI workflow `build-apk-debug.yml` running `gradle test -PversionCode=13 -PversionName="0.1.0-debug.0013"`:
```
SkylarCorePhase3Test > classMethod FAILED
    java.lang.AssertionError at MavenArtifactFetcher.java:129
        Caused by: java.util.concurrent.ExecutionException at AbstractFuture.java:292
            Caused by: java.net.SocketException at NioSocketImpl.java:318

> Task :app:testDebugUnitTest FAILED
43 tests completed, 1 failed
```

---

## 2. Root Cause Analysis

1. **Dual SDK Fetching in CI**:
   - The test classes `LaneAEndToEndPhase7Test`, `LaneBEndToEndPhase8Test`, and `SkylarCredentialBootstrapPhase6Test` were configured with `@Config(manifest = Config.NONE)`, running against the project's target SDK 36 (`android-all-instrumented-16-robolectric-13921718-i7.jar`, 213 MB).
   - In contrast, `SkylarCorePhase3Test` and `SkylarMockTargetDispatchTest` had a legacy hardcoded configuration `@Config(sdk = [34])`.
   - When `gradle test` executed in CI, Robolectric initially resolved SDK 36 for the first tests, but then attempted to dynamically download a second 151 MB jar (`android-all-instrumented-14-robolectric-10818077-i7.jar` for SDK 34) in the middle of test execution using `MavenArtifactFetcher`.

2. **Socket Resets & Stale Keep-Alive**:
   - Robolectric's `MavenArtifactFetcher` uses `HttpURLConnection` without retry logic or progress recovery.
   - Long-lived test runs reusing HTTP keep-alive connections or encountering transient network timeouts caused `java.net.SocketException at NioSocketImpl.java:318` when pulling the secondary 151 MB jar.

3. **Absence of CI M2 Caching**:
   - `.github/workflows/build-apk-debug.yml` utilized `gradle/actions/setup-gradle@v4` (which caches `~/.gradle/caches`), but did not cache `~/.m2/repository/org/robolectric`.
   - Every GitHub Actions runner started with an empty `.m2` repository, forcing heavy network downloads on every test run.

---

## 3. Prior Logging Gaps Audit (AGENTS.md Section 3.1)

- Checked `issues/pending/` before starting work.
- `PRIOR LOGGING GAPS FOUND: none` (directory contained only `.gitkeep`).

---

## 4. Version Increment Assessment (AGENTS.md Section 2)

- **Assessed Probability Score:** 95 / 100 (> 75 threshold).
- **Justification:** Resolving a CI build-blocking test failure requires an updated debug build trigger so CI can cleanly compile and package the debug APK.
- **Version Action Taken:** Incremented `versionCode` by 1 (`13` -> `14`) and `debugCode` by 1 (`0013` -> `0014`). `versionName` preserved as `0.1.0`.

---

## 5. What Was Actually Changed

### 5.1 Test SDK Alignment
- `app/src/test/kotlin/com/inscopelabs/abx/skylar/SkylarCorePhase3Test.kt`:
  - Replaced `@Config(sdk = [34])` with `@Config(manifest = Config.NONE)`.
- `app/src/test/kotlin/com/inscopelabs/abx/skylar/SkylarMockTargetDispatchTest.kt`:
  - Replaced `@Config(sdk = [34])` with `@Config(manifest = Config.NONE)`.
- **Result:** All Robolectric test suites across the repository now uniformly execute against the application target SDK (36), eliminating redundant multi-SDK artifact downloads.

### 5.2 Build Configuration & Network Resiliency
- `app/build.gradle.kts`:
  - Configured `testOptions.unitTests.all` to set JVM socket timeouts and disable stale connection keep-alives:
    ```kotlin
    all {
      (it as? org.gradle.api.tasks.testing.Test)?.apply {
        systemProperty("sun.net.client.defaultConnectTimeout", "60000")
        systemProperty("sun.net.client.defaultReadTimeout", "120000")
        systemProperty("http.keepAlive", "false")
      }
    }
    ```

### 5.3 CI Workflow Caching
- `.github/workflows/build-apk-debug.yml`:
  - Added an `actions/cache@v4` step targeting `~/.m2/repository/org/robolectric` keyed on `gradle/libs.versions.toml`:
    ```yaml
    - name: Cache Robolectric android-all jars
      uses: actions/cache@v4
      with:
        path: ~/.m2/repository/org/robolectric
        key: ${{ runner.os }}-robolectric-${{ hashFiles('gradle/libs.versions.toml') }}
        restore-keys: |
          ${{ runner.os }}-robolectric-
    ```

### 5.4 Version Properties
- `version.properties`:
  - Updated `versionCode=14` and `debugCode=0014`.

---

## 6. Single-Responsibility & File Size Compliance (AGENTS.md Section 4 & 4.1)

All touched files strictly adhere to role boundaries and are well under the 500-line logic file threshold:
- `app/build.gradle.kts`: 105 lines (< 500 lines).
- `app/src/test/kotlin/.../SkylarCorePhase3Test.kt`: 443 lines (< 500 lines).
- `app/src/test/kotlin/.../SkylarMockTargetDispatchTest.kt`: 375 lines (< 500 lines).
- `.github/workflows/build-apk-debug.yml`: 93 lines.
- `version.properties`: 3 lines.

---

## 7. Verification Commands & Results

1. **`compile_applet`**:
   - Output: `Build succeeded - the applet is compiled`.
2. **`gradle :app:testDebugUnitTest`**:
   - Output: `BUILD SUCCESSFUL in 30s` (47 actionable tasks: 2 executed, 45 up-to-date).
3. **`gradle test -PversionCode=14 -PversionName="0.1.0-debug.0014"`**:
   - Output: `BUILD SUCCESSFUL in 50s` (60 actionable tasks: 26 executed, 2 from cache, 32 up-to-date). All tests in `:libs:skylar-envelope` and `:app` passed with zero failures.

---

## 8. Assumptions and Unverified Areas

- Network connectivity to Maven Central in GitHub Actions runners can occasionally suffer from transient packet resets; the combination of SDK unification, connection timeouts, `http.keepAlive=false`, and `~/.m2` caching provides multi-layer protection against this failure mode.
