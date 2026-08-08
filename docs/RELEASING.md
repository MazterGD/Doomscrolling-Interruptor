# Releasing

Cutting a release is: bump the version, push a tag, done. Everything else — building,
signing, verifying and publishing — happens in CI from the tag alone.

The one-time setup below has to be completed first, or the release workflow will fail on
purpose with a message pointing back here.

---

## One-time setup

### 1. The signing key

A release key already exists at `~/keys/interruptor-release.keystore`, with its password in
the git-ignored `keystore.properties` at the repo root.

**This key is permanent.** Android identifies an app by its signing certificate, so if it is
lost, existing installs can never be updated — users would have to uninstall (losing their
settings) and reinstall. If it leaks, whoever holds it can publish an update that every
existing install accepts as genuine.

Back up **both** files somewhere durable and private — a password manager's file attachment,
or an encrypted archive held offline. Not the repository, and not a cloud drive that syncs
to shared machines.

Certificate fingerprint, safe to publish:

```
SHA-256 23:FF:FA:C9:36:6D:22:D6:CF:33:DA:88:62:FD:3E:6B:04:6A:86:09:B1:50:8E:CE:2A:0D:EA:80:B0:DD:B8:4F
```

<details>
<summary>Recreating the key from scratch (only if starting over)</summary>

```bash
keytool -genkeypair \
  -keystore ~/keys/interruptor-release.keystore \
  -alias interruptor \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Doomscrolling Interruptor, O=MazterGD"
```

A new key means a new fingerprint. Update the `expected=` value in
`.github/workflows/release.yml` and the fingerprint published in `README.md`, and be aware
that anyone running the old build must uninstall before they can install the new one.

</details>

### 2. GitHub Secrets

The workflow reconstitutes the keystore from a secret, so it never lives in the repository.

Produce the base64 of the keystore:

```bash
# Git Bash / Linux / macOS
base64 -w0 ~/keys/interruptor-release.keystore > keystore.b64
```

```powershell
# PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\keys\interruptor-release.keystore")) `
  | Set-Content keystore.b64 -NoNewline
```

Then add four secrets under **Settings → Secrets and variables → Actions → New repository
secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the entire contents of `keystore.b64` |
| `KEYSTORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `KEY_ALIAS` | `interruptor` |
| `KEY_PASSWORD` | `keyPassword` from `keystore.properties` |

Delete `keystore.b64` afterwards — it is the signing key in plain text:

```bash
rm keystore.b64
```

---

## Cutting a release

### 1. Bump the version

In [`app/build.gradle.kts`](../app/build.gradle.kts):

```kotlin
versionCode = 2          // must increase every release, no exceptions
versionName = "1.1.0"    // must equal the tag, minus the leading v
```

`versionCode` is what Android compares when deciding whether an APK is an update. If it does
not increase, the install is rejected as a downgrade. `versionName` is the human-facing
string; the workflow refuses to publish if it disagrees with the tag, so a mismatch fails
loudly at the start rather than producing an untraceable artifact.

### 2. Commit and tag

```bash
git add app/build.gradle.kts
git commit -m "Release 1.1.0"
git push origin main

git tag v1.1.0
git push origin v1.1.0
```

### 3. Watch it publish

The tag starts `.github/workflows/release.yml`, which:

1. checks the tag matches `versionName`;
2. restores the keystore from secrets;
3. builds `assembleRelease` (R8 + resource shrinking);
4. asserts the APK declares no `INTERNET` permission;
5. asserts it was signed by the expected certificate;
6. attaches `interruptor-<version>.apk` and its `.sha256` to a new GitHub Release.

Any of steps 1, 4 or 5 failing stops the release rather than publishing something wrong.

---

## Building locally

Debug build, for development:

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Signed release build — needs `keystore.properties` present:

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` the build still succeeds but produces
`app-release-unsigned.apk`, which cannot be installed. That is deliberate: a fresh clone
builds without needing secrets.

Verify what you built:

```bash
SDK=$ANDROID_HOME/build-tools/35.0.0
$SDK/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
$SDK/aapt2 dump permissions app/build/outputs/apk/release/app-release.apk
```

The permission dump should list only
`io.github.maztergd.interruptor.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — a signature-level
permission AndroidX declares over the app's own package to keep runtime-registered broadcast
receivers unexported. It confers no device capability. Anything else, and especially
`android.permission.INTERNET`, is a bug.

---

## Installing on a device

Google Play Protect blocks sideloaded apps that declare an accessibility service, so
installing from a browser or file manager will fail. Use adb:

```bash
adb install -r interruptor-1.1.0.apk
```

The README explains why, and what to tell users who are not comfortable with adb.
