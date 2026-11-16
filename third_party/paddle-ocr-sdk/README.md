# Paddle OCR Android SDK source fixture

Unmodified 26 Kotlin main source files from PaddleOCR commit `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`, under `deploy/ppocr-android/ppocr-sdk`. Original copyright and Apache-2.0 headers and repository LICENSE are retained. Exact source paths, Git blob identities and SHA-256 values are in upstream.json.

Used only by the separate `visionprobe` validation APK. The upstream Gradle/plugin setup is replaced by that module’s build configuration using this repository’s locked Android/JDK toolchain; Kotlin source is unchanged. Models and native/runtime dependencies have separate provenance. The production `app` does not depend on this source.
