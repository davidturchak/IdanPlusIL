# IdanPlusIL

An Android TV live channel streaming app.

> **Status: v1, working.** A channel grid and a Media3 player, running on Android
> TV. 15 live channels, all verified playing. Streams resolve on the device; there
> is no server.

## What this is

A ground-up Android TV app (Compose for TV + Media3) for live channel playback. The design
starts from one premise: **a live channel is not a URL, it is a resolution
procedure that runs at play time.** Stream URLs rot — CDNs rotate, tokens expire
in minutes, providers restructure their pages — so the app models a channel as
metadata plus an async, fallible, cheap-to-rerun resolver, and everything else
follows from that.

## Architecture

The full reference lives in [`.claude/skills/live-tv-streaming/`](.claude/skills/live-tv-streaming/):

| Document | Covers |
|---|---|
| [`SKILL.md`](.claude/skills/live-tv-streaming/SKILL.md) | Layer map, resolver contract, three-tier fallback, **the implementation map of this repo**, the `channels.json` schema, the "a channel stopped working" procedure, and the gotchas |
| [`references/resolver-patterns.md`](.claude/skills/live-tv-streaming/references/resolver-patterns.md) | The five stream resolution techniques and when each applies |
| [`references/player-and-http.md`](.claude/skills/live-tv-streaming/references/player-and-http.md) | Media source construction, format sniffing, error policy, cookie/header propagation |

The short version:

```
ChannelCatalog      id, title, number, epgId          <- channels.json
      |
RemoteConfig        per-channel {show, force, stream}  <- raw.githubusercontent
      |
StreamResolver      one technique per channel
      |  |-- direct manifest
      |  |-- HTML + embedded JSON
      |  |-- iframe chase
      |  |-- platform API (Kaltura)
      |  `-- entitlement / token
      |
Option picker       accumulate, sort by priority, fall back on error
      |
PlayerHost          Media3 (ExoPlayer)
```

Three patterns carry most of the weight:

- **Three-tier fallback.** A remote `force` flag bypasses the resolver entirely,
  then live resolution, then a static default. The flag is a **static-config kill
  switch** - a JSON file on a CDN, with no server to run: when a source changes
  and a resolver breaks, you edit one line and installed clients recover without
  an app release.
- **Resolvers never throw.** They degrade to a configured fallback. A channel
  playing a stale stream beats a channel showing an error.
- **Resolvers accumulate options rather than pick one**, so the selection layer
  and the player error policy both have somewhere to fall back to.

### Settled decisions

**Stream resolution runs on the device, and there is no backend.** The app ports
the resolution techniques directly and reads `config/channels.json` from a raw
GitHub URL for per-channel `{show, force, stream}`.

The recoverability a server would have provided is bought back a different way:
**techniques live in code, parameters live in config.** Compiled Kotlin ships only
the five techniques. Everything that actually breaks - CSS selectors, JSON
pointers, platform ids, entitlement parameter names, browser header sets, the URLs
themselves - lives in `channels.json`. A broadcaster changing their markup is a
one-line edit to a JSON file, not a release. The `force` flag remains the kill
switch, resolvers stay total, and the option ladder means a failed resolution
still has somewhere to fall back to.

**The app ships Media3 only.** No second player engine. A stream Media3 cannot
play is a backend sourcing problem, not a reason to bundle another decoder — that
choice keeps tens of megabytes of native libraries out of the APK. If a fallback
engine ever becomes unavoidable, it goes behind a feature module rather than into
the base APK.

## Repository layout

```
app/                                Android TV client - Compose for TV + Media3
resolver/                           pure JVM: resolution techniques, config,
                                    the fallback ladder. No android.* imports,
                                    so it is unit-testable without an emulator.
config/channels.json                the published channel list and kill switch
config/update.json                  the published update manifest (written by
                                    tools/release.sh, read by installed TVs)
tools/release.sh                    cuts a release: bump, build, sign-check,
                                    GitHub Release, then publish the manifest
tools/branding/build_assets.py      generates icons/banner from the source logo
.claude/skills/live-tv-streaming/   architecture reference (loads automatically
                                    when working on playback in this repo)
```

## Build

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties   # or export ANDROID_HOME
./gradlew :app:assembleDebug
./gradlew :resolver:test          # JVM only, no emulator needed
```

A fresh clone has neither `local.properties` nor `ANDROID_HOME`; without one of
them the build stops with `SDK location not found`.

Toolchain pins that matter: Gradle 8.11.1 → AGP 8.10.1; compileSdk 35 caps
Media3 at 1.9.4 and Coil at 3.4.0; JDK 17 (the build machine's "java 21" is a JRE
without `javac`, pinned via `org.gradle.java.home`). Kotlin 2.2.20, Compose BOM
2026.03.01, tv-material 1.1.0. minSdk 23.

## Releasing

The app updates itself. On every launch it fetches `config/update.json` from
this repo's `main` a couple of seconds after the grid is up; if the manifest's
`versionCode` is higher than the installed one it offers to download the APK
from the matching GitHub Release, verifies size and SHA-256, and hands the file
to the system installer. A "Check for updates" button in the grid header does
the same on demand. Startup checks are silent on failure - an offline TV opens
normally.

### One-time setup on the release machine

Android only installs an update signed with the **same key** as the build
already on the device, so the release key is permanent. Generate it once, keep
it outside the repository, and back it up off-machine:

```bash
mkdir -p ~/.android-keys
keytool -genkeypair -v -keystore ~/.android-keys/idanplusil-release.jks -storetype PKCS12 \
  -alias idanplusil -keyalg RSA -keysize 2048 -validity 10000
cp keystore.properties.example keystore.properties     # fill in the paths and passwords
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep 'SHA-256'
```

`keystore.properties` and `*.jks` are gitignored. The signer's certificate
digest is committed as `tools/release-signer.sha256` (it is public information),
and the release script refuses to publish an APK signed by anything else.
Without `keystore.properties` the release build is **unsigned** on purpose: an
unsigned APK cannot be installed, whereas a debug-signed one would break the
update path on every TV.

Changing the key ever again means one manual `adb uninstall` and reinstall on
every device.

### Cutting a release

```bash
tools/release.sh 1.2.0 --notes "One line the TV shows on the update prompt"
tools/release.sh 1.2.0 --dry-run     # everything except pushing
```

The script bumps `idanplusil.versionCode`/`versionName` in `gradle.properties`,
runs the JVM tests, builds and sign-checks the APK, writes `config/update.json`,
commits and tags. Then, in this order: pushes the **tag only**, creates the
GitHub Release with `IdanPlusIL-X.Y.Z.apk` attached, waits until the asset URL
resolves, and only then pushes `main`. That ordering is what guarantees a TV
never reads a manifest pointing at an asset that does not exist yet.
raw.githubusercontent.com caches for about five minutes, so TVs launched right
after a release still see the previous manifest.

Manifest shape:

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "apkUrl": "https://github.com/davidturchak/IdanPlusIL/releases/download/v1.1.0/IdanPlusIL-1.1.0.apk",
  "sha256": "…",
  "sizeBytes": 2283452,
  "notes": "One line shown on the prompt"
}
```

If the script fails after the tag was pushed it prints the rollback commands
(delete the release and the remote tag, reset `main`) rather than running them.

### On the TV

Android 8+ asks the user once to allow IdanPlusIL to install unknown apps; the
app opens the right settings screen and continues when you come back. For
testing, grant it over adb:

```bash
adb shell appops set com.idanplusil.tv REQUEST_INSTALL_PACKAGES allow
```

## Maintaining the channel list

`config/channels.json` **is** the channel list. Installed TVs fetch it from this
repo's `main` on every launch and fall back to a cached or bundled copy, so a
change here - including removing a channel - reaches every device with no
rebuild. The bundled copy is only the floor for a first launch with no network.

```bash
./gradlew :resolver:liveCheck
```

Resolves every visible channel concurrently and reports `OK`, `DEGRADED` or
`FAIL`. **`DEGRADED` is the state worth watching**: the channel plays, but only
from its static fallback, which means its resolver has quietly died and no amount
of "does it play" testing would have told you.

To fix a channel: edit its entry (a `direct` resolver takes a single `stream` or
an `options[]` list of candidates), mirror the file to
`resolver/src/main/resources/channels.json` so a fresh install starts from the
same data, re-run `liveCheck`, push. `force: true` plays `stream` and skips the
resolver - the stop-gap when a technique breaks. `show: false` hides a channel.
The full schema and per-technique keys are in the skill document.

## Provenance

The architecture reference was written by analysing a decompiled third-party
Android TV app, as a study of how this class of app is built and — as much as
anything — where it goes wrong. The document is explicit about which patterns are
worth carrying forward and which are anti-patterns to avoid, among them global
mutable HTTP state shared across concurrent resolvers, encoding structured
multi-source data as a synthetic HLS playlist string, and caching tokens without
tracking their expiry.

The decompilation artifacts themselves are **not** part of this repository and are
excluded by `.gitignore`. Only the distilled architecture is committed here.

## Sourcing

Channels should be resolved from feeds you are licensed to distribute, from
providers' documented or public APIs, or from openly published playlists. Where a
provider issues entitlement tokens, honour what the entitlement actually grants.

Brand marks belong to their broadcasters; using one is a matter between you and
them. Secrets belong on a server, never in the client — client-side obfuscation of API
keys does not survive contact with a decompiler. Local credential files
(`secrets.properties`, `.env`, keystores) are gitignored; keep them that way.

## License

[MIT](LICENSE) © 2026 David Turchak
