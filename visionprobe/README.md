# Offline vision probe

This separate validation APK compares Paddle's fixed Android SDK with PP-OCRv6 tiny ONNX and ML Kit Chinese bundled 16.0.1. The production `app` does not depend on it. Both target and test manifests remove INTERNET permission; the device test checks the actual installed permissions before creating either engine. There is no launcher, accessibility service, executor or game input.

Prepare and build with the repository's JDK17/SDK setup:

```sh
python3 tools/qa/prepare_vision_models.py
./gradlew :visionprobe:assembleDebug :visionprobe:assembleDebugAndroidTest :visionprobe:lintDebug
```

The script downloads about 6.3 MB from official model URLs into ignored `.local/` storage, verifies the pinned byte counts and SHA-256, and extracts only named files. The Gradle model guard separately checks each packaged model. The source fixture is under `third_party/paddle-ocr-sdk`, with original license and hashes. Native libraries come from Maven and are locked/verified. No candidate dependency or model is added to the production APK. Production `:app:assembleDebug` was also checked with the probe models temporarily absent.

Select an isolated device explicitly; these commands reset **only this probe's test data**:

```sh
PROBE_SERIAL=emulator-5560
adb -s "$PROBE_SERIAL" install -r visionprobe/build/outputs/apk/debug/visionprobe-debug.apk
adb -s "$PROBE_SERIAL" install -r visionprobe/build/outputs/apk/androidTest/debug/visionprobe-debug-androidTest.apk
adb -s "$PROBE_SERIAL" shell pm clear io.github.yysassist.visionprobe
adb -s "$PROBE_SERIAL" shell am instrument -w -r -e class io.github.yysassist.visionprobe.OfflineVisionProbeTest io.github.yysassist.visionprobe.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$PROBE_SERIAL" exec-out run-as io.github.yysassist.visionprobe cat files/vision-probe/results.json
```

Inspect the instrumentation result (`OK`, or a failure/crash), not just the shell exit code. Each report has a new run ID; a native crash can leave an incomplete engine phase. The integration gate checks offline loading, positive/blank behavior and valid geometry. Exact text and IoU remain reported measurements, rather than asserting unsupported game accuracy. Light/dark generated images are synthetic cases, not held-out game themes. Each image is repeated three times; repetitions are not independent coverage.

Optional private crops are **not in Git**. Place PNGs and `labels.json` under `.local/vision-poc/private-test-assets/private-samples/` before building the test APK, then explicitly pass `-e includePrivateVisionSamples true`. The JSON is an array of `{name, file, labels:[{text,bounds:[left,top,right,bottom]}]}`. For those samples, shared results contain expected-label metrics/counts only. `-e privateVisionDetails true` additionally writes actual readings to the probe's private `private-details.json` for local diagnosis; this file is removed at the start of the next run and must not be committed. Images are never exported by that path.

## Current compatibility findings

The upstream example's Quickbird OpenCV 4.5.3 fails native loading on our API36 ARM64 AVD with missing `__sfp_handle_exceptions`. Official OpenCV 4.14.0 passes that boundary but dies with SIGILL at an SVE `rdvl` instruction during library initialization on this AVD. Official 4.11.0 is currently pinned **only for this 4 KB-page AVD comparison**; Lint still reports its `libc++_shared.so` 16 KB alignment issue. These checks are not suppressed. This is not a production backend decision or a general fix for Android compatibility.

Replace this experimental pin only after a newer or appropriately built native backend is verified on the representative AVD, real device and 16 KB-page environment. Keep the SDK/model unchanged when comparing native compatibility. Next work includes larger/alternative model comparisons, Maa recognition-only, game text/number labels and held-out themes, frame mapping, cancellation/resource lifetime, versioned resources and actual task completion evidence. See the current [results](../docs/development/offline-vision-probe.md).
