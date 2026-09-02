---
name: live-tv-streaming
description: Architecture and patterns for building live TV channel streaming in an Android TV / Leanback app - per-channel stream resolvers, multi-source fallback, remote channel config, token/entitlement handling, and ExoPlayer/VLC playback wiring. Use when implementing, debugging, or designing live channel playback, channel lists, stream resolution, EPG binding, or player backends. Derived from analysis of a decompiled reference app.
---

# Live TV streaming subsystem

Reference architecture for live channel playback on Android TV, extracted from a
decompiled reference app and restated as guidance for a new implementation. The
original is Java 8 + AsyncTask + ExoPlayer 2; recommendations below target Kotlin
+ coroutines + Media3.

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
PlayerHost            ExoPlayer (Media3) primary, libVLC fallback
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
   breaks, you flip a flag server-side and every installed client recovers without
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

Put the resolvers on a server instead. The app calls
`GET /v1/channels/{id}/stream` and receives `{url, headers, cookies, expiresAt,
options[]}`. You then get: scraper fixes deploy in minutes without an app
release, credentials never ship to the client, one implementation serves every
platform, and the device does no HTML parsing. The client keeps only the catalog,
the option picker, and the player — which is most of what this skill describes.

Keep the on-device fallback path for when your backend is unreachable.

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

## Reference files

- `references/resolver-patterns.md` — the five resolution techniques with the
  concrete flow each one follows.
- `references/player-and-http.md` — media source construction, format sniffing,
  error policy, cookie/header propagation, VLC fallback.
