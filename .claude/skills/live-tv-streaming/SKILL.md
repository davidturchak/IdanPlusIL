---
name: live-tv-streaming
description: Architecture and implementation map for IdanPlusIL's live TV streaming - the :resolver module (five resolution techniques, channels.json config schema, fallback ladder, liveCheck harness), the :app Compose-for-TV client (grid, Media3 player), and the self-update/release pipeline (update.json manifest, GitHub Releases, tools/release.sh, release keystore). Use when fixing a channel, adding a channel or technique, debugging playback, touching channels.json, cutting a release, or changing the update flow. Includes the toolchain pins and device facts that bit us, and the reference-app analysis the design came from.
---

# Live TV streaming subsystem

Reference architecture for live channel playback on Android TV, extracted from a
decompiled reference app and restated as guidance for a new implementation. The
original is Java 8 + AsyncTask + ExoPlayer 2; recommendations below target Kotlin
+ coroutines + Media3.

> **Decisions already settled for IdanPlusIL — this document is descriptive, the
> README is authoritative.** Stream resolution runs **on the device**; there is no
> server. The resolution techniques below are therefore the client roadmap, and
> they are implemented in the `:resolver` module. The app ships **Media3 only** —
> the libVLC fallback described later records what the reference app did and is
> not a live option here.
>
> The structural rule that makes on-device resolution maintainable: **techniques
> in code, parameters in config.** Selectors, JSON pointers, platform ids,
> entitlement parameter names and header sets all live in `config/channels.json`,
> so the things that break are pushed rather than released.

## The core problem

A live channel is not a URL. It is a *resolution procedure* that runs at play time
and yields a currently-valid stream URL plus the HTTP context needed to fetch it
(User-Agent, cookies, referer, sometimes a short-lived token). URLs rot: CDNs
rotate, tokens expire in minutes, broadcasters restructure their pages. Every
design decision below follows from that.

Design the channel as `Channel(id, metadata, resolver)` where the resolver is
async, fallible, and cheap to re-run. Never cache a resolved URL longer than its
token lifetime.

## Layer map

```
ChannelCatalog        static metadata: id, title, logo, epgId, category, sort
      │
RemoteConfig          per-channel {show, force, stream} fetched at startup
      │
StreamResolver        one per channel/technique; returns List<StreamOption>
      │  ├── direct manifest        (config URL passed through)
      │  ├── HTML + embedded JSON   (scrape <script type=application/json>)
      │  ├── iframe chase           (page → iframe src → page → .m3u8)
      │  ├── platform API           (e.g. Kaltura multirequest)
      │  └── entitlement/token      (params → media id → token → CDN URL)
      │
StreamOption picker   auto-select best, or present a quality/source menu
      │
PlayerHost            ExoPlayer (Media3) - this repo ships Media3 only
```

Source anchors, relative to the reference app's decompiled source root:

| Layer | File |
|---|---|
| Channel catalog | `Utils.java:700-800` (`VideoBuilder` per channel) |
| Remote config load | `ui/MainFragment.java:263-380` |
| Config accessors | `LiveProperties.java` |
| Resolvers | `Tasks/LiveTv/Channel*AsyncTask.java` |
| Resolver callback | `Interfaces/OnChannelLoadingTaskCompleted.java` |
| Retrofit endpoints | `Interfaces/IChannel*Service.java` |
| HTTP wrappers | `Services/Channel*Service.java` |
| Option parse/select | `Utils.getStreamQualityLink()` |
| Dispatch | `Utils.PlayCH11` / `PlayCH13` etc., ~`Utils.java:3050+` |
| ExoPlayer wiring | `ui/Players/PlaybackFragment.java:1400-1580` |
| VLC backend | `ui/Players/LibVLCFragment.java` |

## Resolver contract

Every channel resolver has the same shape. In the original:

```java
public class Channel11AsyncTask extends AsyncTask<Video, Void, Video> {
    onPreExecute()   -> SpinnerFragment.ShowSpinner(activity, fragmentId)
    doInBackground() -> resolve, on any failure fall back
    onPostExecute()  -> RemoveSpinner + listener.onChannelLoadingTaskCompleted(video)
}
```

For a new app, keep the contract, drop AsyncTask:

```kotlin
fun interface StreamResolver {
    suspend fun resolve(channel: Channel): List<StreamOption>
}

data class StreamOption(
    val url: String,
    val label: String,        // "HD", "DVR", "site source 2"
    val priority: Int,        // higher = preferred
    val headers: Map<String, String> = emptyMap(),
    val cookies: String? = null,
    val expiresAt: Instant? = null,
)
```

Rules that the original gets right and are worth keeping:

- **Every resolver is total.** It never propagates an exception to the caller; it
  degrades to the configured fallback URL. A channel that plays a stale stream
  beats a channel that shows an error.
- **Resolvers accumulate rather than pick.** `Channel11AsyncTask` concatenates a
  primary source *and* a closed-captions variant *and* an iframe-derived source,
  then lets the selection layer choose. More options = more chances one works.
- **Loading UI is the resolver's job**, because only it knows when work starts and
  ends. Hoist this into your player's state machine (`Resolving → Buffering →
  Playing → Failed`) rather than poking a spinner fragment directly.

## Three-tier fallback

Every resolver in the original follows this ladder, and you should too:

1. **Force flag** — `LiveProperties.isCH11force()`. If the remote config sets
   `force: true`, skip resolution entirely and play the config-supplied URL. This
   is the kill switch: when a broadcaster changes their site and the scraper
   breaks, you edit one line in the published config and every installed client recovers without
   an app update.
2. **Live resolution** — run the scraper/API flow.
3. **Static fallback** — the URL compiled into the channel catalog.

```kotlin
suspend fun resolveChannel(ch: Channel): List<StreamOption> {
    val cfg = remoteConfig.forChannel(ch.id)
    if (cfg?.force == true) return listOf(StreamOption(cfg.stream, "config", 100))
    return runCatching { ch.resolver.resolve(ch) }
        .getOrNull()?.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(
            cfg?.stream?.let { StreamOption(it, "config", 50) },
            StreamOption(ch.fallbackUrl, "default", 10),
        )
}
```

## Remote channel config

Fetched at startup, cached to `filesDir/version.txt`, shape:

```json
{
  "live": {
    "11": { "show": true, "force": false, "stream": "https://.../master.m3u8" },
    "12": { "show": true, "force": false, "stream": "..." }
  },
  "update": { "servers": { ... }, "new_version_message": "..." },
  "system_message": { "time-date": "dd/MM/yy", "message": "..." }
}
```

`show` controls catalog visibility, `force` bypasses the resolver, `stream` is
both the forced URL and the fallback. Three fields per channel is the right
minimum. Keep the config a plain static JSON file behind a CDN — it is read on
every cold start and must never be the reason the app fails to open. Parse it
defensively: the original wraps each channel in its own try/catch so one
malformed entry does not blank the whole lineup, and you should do the same
(though a schema-validated `Map<String, ChannelConfig>` with `runCatching` per
entry is cleaner than nested try blocks).

## Multi-source transport: read this before copying it

The original encodes its resolved option list as a **synthetic HLS master
playlist string** and passes it through the `Video.videoUrl` field:

```
#EXT-X-STREAM-INF:BANDWIDTH=999,...,RESOLUTION=<Hebrew label>,FRAME-RATE=25.000
https://cdn.example/primary/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=888,...,RESOLUTION=<another label>,FRAME-RATE=25.000
https://cdn.example/secondary/index.m3u8
```

`Utils.getStreamQualityLink(String)` then regex-parses it back into a
`LinkedHashMap<label, entry>` for the quality menu, and the two-arg overload picks
the highest `BANDWIDTH` for auto-play. `BANDWIDTH` is abused as a priority rank
(999/888/777/666/555) and `RESOLUTION` as a human-readable label.

It works, and it has one real virtue: a single-source channel and a multi-source
channel take the identical code path, and a bare URL passes through unchanged.
But it round-trips structured data through a string format that was never meant
to carry it, the labels break if they contain a comma, and it is why the codebase
is full of `substring`/`indexOf` juggling.

**In a new app, use `List<StreamOption>` directly.** Keep the priority-rank idea,
drop the playlist encoding. If you want the player's own ABR to choose between
genuine bitrate variants, that is what a *real* master playlist from the CDN is
for — don't synthesize one.

## Token and session caching

Tokenized channels (the entitlement flow in `Channel12AsyncTask.java`) cache
the issued token in `SharedPreferences("LIVEC")` under keys like `C12_TIK` and
`C12_URL`, and skip the whole entitlement round-trip while a cached token exists.

The bug to avoid: **the original never stores an expiry**, so a stale token
produces a 403 and the app leans on the player's error handling to recover.
Store `expiresAt` alongside the token, treat it as expired a minute early, and
re-resolve proactively. See `references/player-and-http.md` for the 403 handling
that papers over this.

## HTTP layer

The original routes everything through `WebapiSingleton`: a global mutable
OkHttp/Retrofit holder that each `Channel*Service` constructor **reconfigures**
before use — `clearHeaders()`, `setHeaders(...)`, `initCookieJar()`,
`initRetrofitWebApi(...)`. Per-source Retrofit interfaces
(`Interfaces/IChannel12Service.java`) declare the endpoints; the wrapper class
sets up the client.

The Retrofit-interface-per-source part is good. **The singleton is not** — it is
global mutable state mutated from background threads, so two channels resolving
concurrently will corrupt each other's headers and cookie jar. This is very
likely behind intermittent "channel works alone, fails when I switch fast"
behavior.

Build one `OkHttpClient` per source, sharing the connection pool and dispatcher:

```kotlin
val base = OkHttpClient.Builder().build()
fun clientFor(source: SourceSpec) = base.newBuilder()
    .cookieJar(source.cookieJar())
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().apply {
            source.headers.forEach { (k, v) -> header(k, v) }
        }.build())
    }
    .build()
```

Header realism matters for scrape-based resolvers: `Channel12AsyncTask` sends a
full Chrome header set (`sec-ch-ua`, `sec-fetch-*`, `accept-language`,
`upgrade-insecure-requests`). Sites reject default OkHttp fingerprints. Keep
these in config, not code — they need updating as browser versions move.

## Player layer

Two backends, selected per stream. ExoPlayer/Media3 is the default; libVLC
(`LibVLCFragment`) handles what ExoPlayer refuses — odd codecs, some RTSP/RTMP,
malformed manifests. Bundling both roughly doubles APK size (VLC is ~42 MB per
ABI here); decide deliberately, and consider shipping VLC only in an ABI split or
via Play Feature Delivery.

Critical details, expanded in `references/player-and-http.md`:

- Build media sources over an **`OkHttpDataSource.Factory` wrapping the same
  client that resolved the stream**, so cookies and User-Agent carry into segment
  requests. Tokenized CDNs check them on every segment, not just the manifest.
- Sniff the container with `Util.inferContentType(uri)`, then fall back to
  substring checks (`contains("m3u")`, `contains("dash")`) — live URLs frequently
  have no useful extension.
- Install a `LoadErrorHandlingPolicy` that blacklists a variant for ~60 s on
  HTTP 403 rather than failing playback. That is the token-expiry signal.
- Set `setAllowChunklessPreparation(true)` for HLS; it measurably cuts channel
  zap time.

## EPG

Channels carry `epgId` separately from the stream `tag` — deliberately, since the
guide provider's identifier rarely matches your internal channel id. Keep them
distinct from day one. EPG fetch is a plain `@GET @Url` returning XMLTV
(`Interfaces/IEpgService.java`), parsed into `EpgItemDto`.

## Building a new app: the one structural change worth making

The original resolves streams **on-device**, which is why it needs per-channel
scrapers, obfuscated endpoint parameters, native key storage, and a remote kill
switch per channel — and why a broadcaster's site change breaks every installed
client until users update.

Putting the resolvers on a server would fix that: scraper fixes deploy in minutes
without an app release, credentials never ship to the client, and one
implementation serves every platform.

**IdanPlusIL consciously accepted the trade-off instead**, because running a
server is a cost the project did not want, and because the reference app
demonstrates that a static JSON file on a CDN recovers most of the benefit. The
mitigations that make on-device resolution survivable, and which the
implementation relies on, are:

- **The `force` kill switch** in `channels.json` — flip it and every installed
  client bypasses a broken resolver with no release.
- **Parameters in config, not code** — the selectors, ids and header sets that
  break are edited in JSON, so most "resolver breakage" never needs new code at
  all.
- **Resolver totality and the option ladder** — a broken resolver degrades to a
  configured stream rather than showing an error.
- **A health harness** (`./gradlew :resolver:liveCheck`) that distinguishes a
  channel which genuinely resolves from one that merely still plays off its
  fallback. Without it, a dead resolver is invisible — which is exactly the state
  several of the reference app's resolvers are in.

What you give up is credential safety: anything genuinely secret still cannot
live in the client. Do not put one there.

## Sourcing and compliance

When you build the new app, resolve streams from feeds you are licensed to
distribute, from broadcasters' documented/public APIs, or from openly published
playlists. The token and entitlement flows in the original exist to enforce
broadcaster access rules — treat obtaining a token and then applying it to CDN
paths the entitlement response did not grant as a pattern to *understand*, not to
reproduce. Note also that the original's string encryption (AES-CBC + rot13 with
keys split across a stripped JNI `libkeys.so`) provides no real protection: the
whole scheme was recoverable from the APK in a single pass. Anything that must
stay secret belongs on a server you control.


## Implementation in this repo

Everything above describes the reference app. This section is the map of what
IdanPlusIL actually built, so a fresh session can start here.

### Where things live

| Concept | Location |
|---|---|
| Resolver contract (total, never throws) | `resolver/.../StreamResolver.kt` |
| Single dispatch point, budget + totality enforcement | `resolver/.../ResolverRegistry.kt` |
| Three-tier ladder (force → live → fallbacks), option accumulation | `resolver/.../ChannelResolutionService.kt` |
| Techniques | `resolver/.../technique/{Direct,HtmlJson,IframeChase,Kaltura,Entitlement}Resolver.kt` |
| One immutable OkHttp client per source, derived from a shared base | `resolver/.../http/HttpClientFactory.kt` |
| Config model, defensive per-entry parsing, remote-authoritative merge over the bundled floor | `resolver/.../config/{RemoteChannelConfig,ResolverSpec,ConfigLoader,BundledDefaults}.kt` |
| Token cache with expiry (60 s skew) | `resolver/.../token/TokenStore.kt` |
| Published channel list / kill switch | `config/channels.json` (fetched from raw GitHub `main`) |
| Bundled cold-start copy - keep identical to the published one | `resolver/src/main/resources/channels.json` |
| Live health harness | `resolver/src/liveCheck/.../LiveCheckMain.kt` → `./gradlew :resolver:liveCheck` |
| Fixture tests, fake HTTP facade | `resolver/src/test/...`, `FakeHttpFacade.kt` |
| Config fetch (ETag), disk cache (temp-then-rename), 3-source ladder | `app/.../data/config/{RemoteConfigSource,ConfigCache,ChannelRepository}.kt` |
| Dependency graph (hand-rolled, no Hilt) | `app/.../di/AppContainer.kt` |
| Media3 assembly: SurfaceView, decoder fallback, chunkless HLS, 30 s buffer | `app/.../player/PlayerFactory.kt` |
| 403/410 → exclude + re-resolve | `app/.../player/LoadErrorPolicy.kt` |
| Player state machine, option stepping, one re-resolve, 12 s absolute deadline, zapping | `app/.../ui/player/PlayerViewModel.kt` |
| Player keys (stand down when the failure pane is up) | `app/.../ui/player/PlayerActivity.kt` |
| Grid, card (full-bleed art, number-badge monogram fallback, 4-signal focus), header: logo, offline chip, version label | `app/.../ui/channels/` |
| Card art lookup: `logo` config value → URL or bundled drawable id | `app/.../ui/channels/ChannelLogo.kt`, `app/src/main/res/drawable-nodpi/logo_*.webp`, `res/raw/keep.xml` |
| Version label = "check for updates" control (`VersionBadge`: chrome-free tv-material `Surface`, thin ring on focus; label swaps to Checking… / You're up to date / Update to X) | `app/.../ui/channels/ChannelsScreen.kt` |
| Self-update: manifest model, version check, streaming download + SHA-256, APK cache | `app/.../data/update/{UpdateManifest,UpdateChecker,ApkDownloader,ApkStore}.kt` (pure JVM, tested) |
| Self-update: install launch, unknown-sources gating | `app/.../update/UpdateInstaller.kt` |
| Self-update: state machine and pane (Available/Downloading/Permission/Ready/Failed replace the grid, no Dialog; Checking/UpToDate stay inline in the header) | `app/.../ui/channels/{UpdateUiState,UpdateViewModel,UpdatePane}.kt` |
| Published update manifest | `config/update.json` (written by `tools/release.sh`, never by hand) |
| Release pipeline (bump, build, signer guard, GitHub Release, then manifest) | `tools/release.sh`, `tools/release-signer.sha256` |
| Update-flow test build (release-signed, versionCode 1, `--install` replaces the TV app) | `tools/test-build.sh` |
| Theme (LocalContentColor wired explicitly), tokens | `app/.../ui/theme/` |
| Logo keying pipeline | `tools/branding/build_assets.py` |
| Channel card art normalisation (one-off authoring) | `tools/branding/build_channel_logos.py` |

Package: `com.idanplusil.tv` (app), `com.idanplusil.resolver` (JVM module).
Debug build has applicationId suffix `.debug`.

### `channels.json` schema

```json
{
  "schema": 1,
  "updatedAt": 1756809600000,
  "headerSets": { "browser_chrome": { "User-Agent": "…", "sec-ch-ua": "…" } },
  "live": {
    "<id>": {
      "show": true, "force": false, "sort": 10,
      "title": "Kan 11", "epgId": "11", "logo": "logo_11",
      "stream": "https://…/playlist.m3u8",
      "resolver": { "type": "…", "headersRef": "browser_chrome", … }
    }
  }
}
```

- `show` hides a channel; `force: true` plays `stream` and skips the resolver
  entirely (**requires a non-blank `stream`**); `stream` is also the tier-3
  fallback. `sort` orders the grid. `id` is the card's number badge when numeric.
- `logo` is either an `http(s)` URL or the bare name of a drawable bundled in
  `app/src/main/res/drawable-nodpi` (`logo_11`) - prefix decides, as in the
  reference app's card presenter. Bundled is the norm: card art renders offline
  on first paint and rarely changes. Regenerate the set with
  `tools/branding/build_channel_logos.py <dir of <id>.png>` (letterboxes each
  source onto its own edge colour at 640x360 WebP). `res/raw/keep.xml` pins
  `@drawable/logo_*` because the name lookup is invisible to the shrinker. An
  unknown name falls back to the monogram, so a typo costs one logo.
- `resolver.type` is one of `direct | html_json | iframe_chase | kaltura |
  entitlement`. Unknown types fail that one channel, not the file.
- `headersRef` names an entry in `headerSets`; headers ride on resolution *and*
  on every segment request.
- Per-type keys:
  - `direct`: `stream` **or** `options: [{url, label, priority, container}]`.
    `options` replaces the reference app's synthetic `#EXT-X-STREAM-INF` string.
    `container: "dash"` for `.livx`/DASH manifests with no useful extension.
  - `entitlement`: `entitlementUrl`, `etParam/etValue`, `lpParam`, `cdnParam`,
    `cdn`, `ticketTtlSeconds`, and `stream` **or** `options[]`. One ticket is
    requested per manifest (the provider's own flow) - never one ticket stamped
    across paths the response did not name.
  - `kaltura`: `serviceUrl`, `partnerId`, `widgetId`, `entryId`, `referer`,
    `userAgent`, `extraOptions[]`.
  - `iframe_chase`: `pageUrl` (must be a page, a manifest is rejected),
    `iframeSelector`, `maxHops`, `sendReferer`, `manifestPattern`.
  - `html_json`: `pages[{url,label,priority}]`, `jsonSelector`, `jsonPointer`,
    `iframeSelector`. Implemented and tested, currently unused - see gotchas.
- Config priorities may tie; resolvers make them strictly distinct.

### Operating procedure: a channel stopped working

1. `./gradlew :resolver:liveCheck`. `FAIL` = nothing plays. `DEGRADED` = plays
   only off the fallback, i.e. the resolver is dead and users would never know.
2. **Check the reference app's *published* config before anything else.** Its
   compiled-in fallbacks (what a decompile shows) are years stale; its remote
   config has `force: true` on every channel and current URLs, several as
   multi-line playlists to split into `options[]`. The URL is returned by a JNI
   call and sits as a plain string in its native library - it contains the
   reference app's name and **must never appear in a tracked file**.
3. Edit `config/channels.json` (and mirror to
   `resolver/src/main/resources/channels.json`), re-run liveCheck, push. No
   rebuild: installed TVs refresh on next launch. Use `force: true` as the
   stop-gap when a technique breaks and a direct URL is known.

### Operating procedure: cutting a release

1. Commit and push the work to `main` (the script refuses a dirty or out-of-sync
   tree). Pushing is the user's step in practice.
2. `tools/release.sh X.Y.Z --notes "one line shown on the TV prompt"`
   (`--dry-run` first if unsure). It bumps `idanplusil.versionCode`/`versionName`
   in `gradle.properties`, runs the JVM tests, builds, checks the signer against
   `tools/release-signer.sha256`, writes `config/update.json`, pushes the tag,
   creates the GitHub Release, waits for the asset, then pushes `main`.
3. **Do not install on the TV.** The user updates from the app (launch, or press
   the version label). raw.githubusercontent lags up to 5 min.
4. To re-test the update flow itself: `tools/test-build.sh --install` puts a
   release-signed versionCode-1 build on the TV; the live release then shows up as
   an update. The user asked to be the one who installs otherwise.

Version label in the header shows `BuildConfig.VERSION_NAME`; it is how the user
confirms which build a TV runs.

### Gotchas that cost time - do not rediscover

- **Never add `focusable()` to a tv-material `Button`/`Surface` modifier.** It
  creates a second focus target outside the component: `requestFocus()` lands on
  the wrapper, the button never draws focused and the centre key does nothing.
  `MessagePane` shipped with this bug in v1; Retry was unreachable.
- **`run-as` does not work on the release build** (not debuggable). Judge the
  update cache from the `update: pruned [...]` log line, not from the filesystem.
- **`android:banner` must be declared on `<application>`.** Without it the TV
  launcher synthesises its own tile from the launcher icon and the app label,
  in its own text colour - on the TCL that was a black label on our near-black
  icon ground. The banner asset had been generated all along but never
  referenced (and so was shrunk out of release builds).
- **To try an unpublished `channels.json` on the TV without pushing:** the
  remote config is authoritative, so the debug build overwrites whatever you
  seed into `files/config/` on the next fetch - unless the fetch returns 304.
  Launch once, read the ETag the app stored (`run-as com.idanplusil.tv.debug
  cat files/config/channels.etag`; it is a *weak* `W/"…"` ETag, not the strong
  one `curl -I` shows), then seed `files/config/channels.json` plus that ETag
  and relaunch. Debug variant only.

- **Kaltura returns XML if `Content-Type` carries a charset.** OkHttp's
  `String.toRequestBody(mediaType)` always appends `; charset=utf-8`; Kaltura
  string-matches `application/json` and silently falls back to empty form
  parsing (HTTP 200, `<xml><result/></xml>`). Post bytes, not a String.
- **Kan's site (`kan.org.il`) 403s any plain HTTP client** even with a full
  Chrome header set - Cloudflare TLS fingerprinting. That is why `html_json` is
  unused and Kan 11 is `direct` with three options. No header config fixes this.
- **tv-material `Text` is black outside a `Surface`.** `LocalContentColor` is not
  derived from the scheme; the theme provides it explicitly.
- **`screencap` shows black where video plays.** `SurfaceView` renders on a
  hardware overlay plane. Judge playback from `logcat | grep MediaCodec` (`ROB:`
  rendered-output-buffers per second), not from screenshots.
- **When the failure pane is up, the player's `onKeyDown` must return `super`**,
  or Retry/Back are unreachable and centre toggles play/pause on a dead stream.
- **ExoPlayer retries a dead host with backoff for 30 s+ before erroring.** The
  ViewModel enforces an absolute 12 s per-channel first-frame deadline.
- **The system "java 21" on the build machine is a JRE with no `javac`.** JDK 17
  is pinned via `org.gradle.java.home` in `gradle.properties`.
- **Version ceilings:** Gradle 8.11.1 caps AGP at 8.10.1; only `android-35` is
  installed, which caps Media3 at 1.9.4 and Coil at 3.4.0 (`minCompileSdk`).
- **Wrapper generation needs a `settings.gradle.kts` to exist first.**
- **Target device** (ADB `192.168.1.195:5555`): TCL, Android 12 / API 31,
  **armeabi-v7a only**, 2.4 GB, MT5896, 1080p UI. Any future native dependency
  must ship a v7a variant. Wake it with `input keyevent KEYCODE_WAKEUP` before
  launching; it sleeps and then `APP_START_CANCELED`s.
- **Two tiny native libs do ship** (Compose graphics-path, DataStore counter);
  both include v7a.
- **Release builds must be signed with the release keystore** (`keystore.properties`
  → `~/.android-keys/idanplusil-release.jks`, gitignored). Without it the build
  is deliberately *unsigned*. A debug-signed release would be rejected as an
  update by every installed TV; changing the key means `adb uninstall` everywhere.
- **Self-update pitfalls:** the FileProvider authority is
  `${applicationId}.updates`, so the `.debug` variant has its own; a GitHub
  release asset's URL uses the uploaded *file name* (`path#label` only changes
  the label); push the tag and create the release *before* pushing `main`, or a
  TV can read a manifest pointing at a 404; raw.githubusercontent lags ~5 min;
  the R8 serializer keep rules were resolver-only until the app got its own
  `@Serializable` type; pre-grant the install permission on a test TV with
  `adb shell appops set com.idanplusil.tv REQUEST_INSTALL_PACKAGES allow`.
- **Repo hygiene:** the reference app's name, its package, the local decompile
  directories (which are named after it), and its config URL never go in a
  tracked file, commit message or comment. Machine-specific ignores live in
  `.git/info/exclude`.

### Deliberately not built (v1 scope)

Favourites, recents, EPG/guide, search, categories/rows, settings (beyond the
self-update check), parental PIN, VOD, radio, IPTV import, subtitle pickers, PiP, Hebrew locale. Hooks are in
place: `Channel.epgId`/`categoryIds`, `ChannelCard(subtitle=)`, `supportsRtl`,
start/end-only padding, all strings in `res/values/strings.xml`, no
`localeFilters`. A JS-rendered-page technique (WebView) was planned for one
channel and is no longer needed - that channel now has a plain HLS URL.

## Reference files

- `references/resolver-patterns.md` — the five resolution techniques with the
  concrete flow each one follows.
- `references/player-and-http.md` — media source construction, format sniffing,
  error policy, cookie/header propagation, VLC fallback.
