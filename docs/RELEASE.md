# Release Guide

## Build Debug APK
```sh
sh gradlew assembleDebug
```
Output:
- `app/build/outputs/apk/debug/app-debug.apk`

## Build Release APK (unsigned by default)
```sh
sh gradlew assembleRelease
```
Output (without signing credentials):
- `app/build/outputs/apk/release/app-release-unsigned.apk`

## Optional: Signed Release APK Configuration
Signing is loaded from environment variables or `local.properties`.

### Keys (local.properties)
```properties
release.signing.storeFile=/absolute/path/to/keystore.jks
release.signing.storePassword=...
release.signing.keyAlias=...
release.signing.keyPassword=...
```

### Environment variable equivalents
- `RELEASE_SIGNING_STOREFILE`
- `RELEASE_SIGNING_STOREPASSWORD`
- `RELEASE_SIGNING_KEYALIAS`
- `RELEASE_SIGNING_KEYPASSWORD`

When all values are present and the keystore exists, `assembleRelease` produces a signed release APK.
If missing, release build still works and produces unsigned output.

## Generate Keystore
```sh
keytool -genkeypair \
  -v \
  -keystore release-key.jks \
  -alias agenticbrowser \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

## Install APK with ADB
```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
For release-signed APK, install that generated artifact similarly.

## Supported Model Formats
- `.litertlm`
- `.task`
- `.bin`

## Expected Model Location
- Downloaded model files are stored in app-internal storage (`context.filesDir`).
- Selected model path is persisted locally and validated on startup.

## Known Limitations
- Local model quality/latency depends on device CPU/GPU and model size.
- Some websites with heavy anti-bot controls may require human-in-the-loop continuation.
- DOM simplification is best-effort and capped for context safety.

## Security Boundaries
- Model output is parsed through strict action schema only.
- Unknown actions/keys or malformed JSON are rejected.
- `CommandExecutor` is the only JavaScript execution path.
- Raw model output is never executed as JavaScript.
- Injected strings are escaped with `JSONObject.quote(...)`.
- WebView restrictions are enabled (`allowFileAccess=false`, `allowContentAccess=false`, `mixedContentMode=never allow`, no JS popups).
