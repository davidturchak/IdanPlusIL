# IdanPlusIL

An Android TV live channel streaming app.

> **Status: v1.** A channel grid and a Media3 player, running on Android TV.
> Streams resolve on the device; there is no server.

## What this is

A ground-up Android TV (Leanback) app for live channel playback. The design
starts from one premise: **a live channel is not a URL, it is a resolution
procedure that runs at play time.** Stream URLs rot — CDNs rotate, tokens expire
in minutes, providers restructure their pages — so the app models a channel as
metadata plus an async, fallible, cheap-to-rerun resolver, and everything else
follows from that.

## Architecture

The full reference lives in [`.claude/skills/live-tv-streaming/`](.claude/skills/live-tv-streaming/):

| Document | Covers |
|---|---|
| [`SKILL.md`](.claude/skills/live-tv-streaming/SKILL.md) | Layer map, resolver contract, three-tier fallback, remote channel config, token caching, EPG binding |
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
the six techniques. Everything that actually breaks - CSS selectors, JSON
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

### Checking the channel list

Channel URLs rot - that is the premise the whole design starts from. To find out
which ones currently work:

```bash
./gradlew :resolver:liveCheck
```

It resolves every visible channel concurrently and reports `OK`, `DEGRADED` or
`FAIL` per channel. **`DEGRADED` is the state worth watching**: the channel plays,
but only from its static fallback, which means its resolver has quietly died and
no amount of "does it play" testing would have told you.

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
