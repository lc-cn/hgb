# Maa recognition-only Android probe

This separate ARM64 experiment validates MaaFramework 5.16.1 through its C API. Production `app` does not depend on this experiment; both now reuse the `vision` library for JNI and subprocess transport. See the [App observation integration](../docs/development/versioned-text-observation.md). No launcher, accessibility service, controller, action, toolkit or agent is registered. Both target/test APKs remove INTERNET permission; installed permission checks run before native loading and comparison.

The seven selected upstream libraries, twelve C headers, converted PP-OCRv6 small files and license are fixed in [`vision/inputs.lock.json`](../vision/inputs.lock.json). Preparation validates the complete official release digest and individual selected bytes. This dependency closure needs MaaFramework, MaaUtils, OpenCV world, ONNX Runtime, FastDeploy, NCNN and libc++; NCNN is transitively required by the binary even though recognition explicitly selects ONNX CPU. Controller, toolkit, agent and plugin libraries are excluded. JNI uses the installed NDK 27.1.12297006/CMake 3.22.1, a static STL and C API ownership; foreign objects are destroyed by their owning C functions.

```sh
python3 tools/qa/prepare_maa_inputs.py
./gradlew :maaprobe:assembleDebug :maaprobe:assembleDebugAndroidTest :maaprobe:lintDebug
```

All generated inputs are ignored. The package guard verifies bytes and rejects undeclared files. No binary or model is committed. Third-party LGPL and model/dependency notices remain relevant to any future distribution; this probe is not a production packaging or licensing determination.

On the explicitly chosen QA device:

```sh
MAA_PROBE_SERIAL=emulator-5560
adb -s "$MAA_PROBE_SERIAL" install -r maaprobe/build/outputs/apk/debug/maaprobe-debug.apk
adb -s "$MAA_PROBE_SERIAL" install -r maaprobe/build/outputs/apk/androidTest/debug/maaprobe-debug-androidTest.apk
adb -s "$MAA_PROBE_SERIAL" shell am instrument -w -r io.github.yysassist.maaprobe.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$MAA_PROBE_SERIAL" exec-out run-as io.github.yysassist.maaprobe cat files/maa-probe/comparison.json
```

Default tests skip the explicitly gated fault experiments and use synthetic inputs only; the default test APK has no private assets. For the prior private sample protocol, build tests with `-PincludePrivateVisionSamples=true`, then run with `-e includePrivateVisionSamples true`; source is `.local/vision-poc/private-test-assets/private-samples/`. This is the same PNG/region/label input protocol as `visionprobe`. `-e privateVisionDetails true` writes raw private diagnostic output locally; it is removed at the start of the next comparison. Private images, diagnostics and test APKs containing them are not committed or published. Shared comparison output omits native recognition text for private cases.

`all` candidates are measured using the same exact-text/IoU/one-to-one-overlap criterion as the earlier probes. `all`, `filtered` and `hit` have different meanings; none establishes a business result. Resource registration is lazy and its time includes the probe's model digest gate; actual native model initialization occurs on first inference. Timing is not directly comparable to eager SDK creation.

## Observed fault and temporary entry mitigation

The explicit corrupt-model experiment crashed the probe process: an upstream background worker terminated with an uncaught `Ort::Exception` while loading invalid ONNX. JNI caller-thread catches cannot intercept that exception. The original crash logs and binary identities are preserved in the [evidence](../docs/development/build-evidence/maa-probe/README.md); it is not a passing reliability test.

`MaaOcr` verifies copied model bytes against APK-compiled digests generated from the lock before creating a foreign worker. A changed-model test verifies rejection and a subsequent valid read in the same process. This is a temporary probe entry mitigation, not an upstream fix or a solution to all native crashes, concurrent file replacement or hangs. This fixed-APK gate now exists only in the direct-ABI test fixture. The App observer instead consumes the version library’s verified resource descriptors and keeps its lease until the shared subprocess proves quiet. Production models belong to task versions and are absent from the main APK.

To reproduce the **known process-terminating** upstream fault separately, the local test package contains `MaaFailureContractTest` gated by `-e allowCorruptMaaModels true`. It deliberately bypasses `MaaOcr` and must not be mixed into the normal batch. The adapter never reports an unfinished or crashed native request as an empty successful recognition.

Recognition calls are synchronous and serialized with close; coroutine cancellation is not implemented as native cancellation. No production StepWorker, input authority, timeout/retry policy or fallback is registered. ELF/APK alignment checks passed at 16KB, while actual execution was only on the 4KB AVD. Phone, Windows emulator, 16KB-kernel, stress and real-claim validation remain open. See [results and limits](../docs/development/maa-recognition-probe.md).

## Separate-process fault boundary

The private `MaaObservationService` is a same-UID process experiment, not a security sandbox. Model/image/result descriptors cross Binder; no controller is bound. A single accepted call owns the child until process termination, and the host waits for Binder death and any result reader before releasing its use callback. A successful empty read remains different from no observation. There is no automatic retry or timeout policy. See [contract and results](../docs/development/maa-process-boundary.md).

On the same isolated QA emulator, run the seven process cases including explicit child-native faults:

```sh
adb -s "$MAA_PROBE_SERIAL" shell am instrument -w -r \
  -e class io.github.yysassist.maaprobe.MaaProcessBoundaryTest \
  -e allowChildNativeFaults true \
  io.github.yysassist.maaprobe.test/androidx.test.runner.AndroidJUnitRunner
```

The invalid ONNX case intentionally has a correct declared digest, reproduces the real upstream SIGABRT only in the child, and verifies an explicit subsequent valid call. The debug block case enters a non-returning C++ wait, then verifies an independent stop. These are not mixed with the old `allowCorruptMaaModels` host-terminating experiment. They do not prove production executor survival or game-result recovery.

## Region and graphic-state experiment

`MaaRegionNative` (library `yys_maa_region_probe`) is a probe-only JNI entry that runs OCR, TemplateMatch or ColorMatch with an explicit ROI in the same MaaFramework session; templates are injected in memory via `MaaResourceOverrideImage`. The production `vision` library is not changed. `MaaRegionStateExperimentTest` is skipped by default. To run it on private frames:

```sh
python3 tools/qa/prepare_region_experiment.py
./gradlew -PincludePrivateRegionExperiment=true :maaprobe:assembleDebug :maaprobe:assembleDebugAndroidTest
adb -s "$MAA_PROBE_SERIAL" shell am instrument -w -r -e class io.github.yysassist.maaprobe.MaaRegionStateExperimentTest \
  -e includePrivateRegionExperiment true io.github.yysassist.maaprobe.test/androidx.test.runner.AndroidJUnitRunner
```

**TemplateMatch terminates the process with SIGILL on Apple-silicon-hosted AVDs** (guest advertises `sve2` without `sve`; OpenCV KleidiCV selects SVE2). Use a device without that mismatch. Rebuild without the property afterwards so the local test APK has no private frames. Results and limits: [region-state evidence](../docs/development/build-evidence/region-state/README.md).
