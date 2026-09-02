# Player and HTTP layer

How a resolved `StreamOption` becomes picture on screen, and the HTTP details that
decide whether it does.

Source anchors: `ui/Players/PlaybackFragment.java` (ExoPlayer host),
`ui/Players/LibVLCFragment.java` (VLC host), `player/MediaSourceHub.java`
(subtitle sources), `Classes/WebapiSingleton.java` (HTTP config).

---

## Carrying HTTP context into playback

The single most common cause of "the resolver found a URL but playback 403s" is
losing the HTTP context between resolution and playback. Cookies set during a
scrape, the User-Agent the CDN fingerprinted, the `Referer` the player host
checked — the segment requests need all of it, not just the manifest request.

The original does this correctly by building media sources over
`OkHttpDataSourceFactory` bound to the same OkHttp client the resolver used
(`PlaybackFragment.java:989`), so the shared cookie jar and UA apply throughout.

Media3 equivalent:

```kotlin
val dataSourceFactory = OkHttpDataSource.Factory(resolverClient)
    .setUserAgent(option.userAgent)
    .setDefaultRequestProperties(option.headers)

val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
    .setAllowChunklessPreparation(true)
    .createMediaSource(MediaItem.fromUri(option.url))
```

Make `StreamOption` carry `headers` and `cookies` so this is mechanical rather
than remembered. If your resolvers move server-side, return those fields in the
API response — they are part of the answer, not an implementation detail.

---

## Format sniffing

Live URLs often carry no useful extension. The original sniffs in two stages
(`PlaybackFragment.VideoDetect`, ~line 1500):

1. `Util.inferContentType(uri)` → `CONTENT_TYPE_DASH / SS / HLS / OTHER`.
2. On `OTHER`, fall back to substring checks on the URL: `.endsWith("anifest")`
   → SmoothStreaming, `contains("m3u")` → HLS, `contains("dash")` → DASH,
   otherwise Progressive.

Keep both stages — stage 2 catches the real-world URLs stage 1 misses. In Media3,
`Util.inferContentType` still exists; prefer setting `MediaItem.mimeType`
explicitly when the resolver already knows the format (technique 4 and 5
resolvers usually do, from the API's `format` field), and only sniff when it
doesn't.

```kotlin
val mediaItem = MediaItem.Builder()
    .setUri(option.url)
    .apply { option.mimeType?.let { setMimeType(it) } }
    .build()
```

---

## Error policy: 403 means "token expired", not "stream dead"

Tokenised live streams fail mid-playback when the token lapses. Default ExoPlayer
behaviour is to surface a fatal error. The original instead installs a custom
`LoadErrorHandlingPolicy` that blacklists the failing variant for 60 s on HTTP 403
and lets playback continue on another variant (`PlaybackFragment.java:1517-1528`):

```kotlin
val policy = object : DefaultLoadErrorHandlingPolicy() {
    override fun getBlacklistDurationMsFor(info: LoadErrorInfo): Long {
        val e = info.exception
        if (e is HttpDataSource.InvalidResponseCodeException) {
            return if (e.responseCode == 403) 60_000L else C.TIME_UNSET
        }
        return super.getBlacklistDurationMsFor(info)
    }
}
```

This is a workaround for not tracking token expiry. Do both: track `expiresAt` and
re-resolve proactively (see SKILL.md), *and* keep the 403 policy as a safety net,
since server-side expiry rarely matches the advertised lifetime exactly.

Recovery ladder on playback failure, in order:
1. 403 → blacklist variant, try next `StreamOption`.
2. All options exhausted → re-run the resolver (token may have simply expired).
3. Resolver fails → fall back to remote-config URL.
4. That fails → surface "channel unavailable", and report which stage failed.

---

## Live-specific tuning

- **`setAllowChunklessPreparation(true)`** for HLS. Skips downloading each variant's
  media playlist during preparation; noticeably faster channel changes.
- **`setLivePresentationDelayMs`** for DASH — the original uses 6000 ms
  (`PlaybackFragment.java:1506`). Trades latency against rebuffer resilience;
  tune per source rather than globally.
- **Preload the next likely channel's resolution** (not its media) during channel
  surfing. Resolution is the slow part for techniques 2-5; a warm token turns a
  three-hop scrape into an instant play.
- **Do not seek on live windows** unless the manifest advertises DVR. The original
  exposes DVR as a separate labelled `StreamOption` where the broadcaster provides
  a distinct DVR manifest, which is the right model: DVR is a different stream,
  not a player mode.

---

## Two backends

> **IdanPlusIL ships Media3 only.** This section records what the reference app
> did; it is not a live option here. The implemented player is
> `app/.../player/PlayerFactory.kt` + `LoadErrorPolicy.kt`.

ExoPlayer/Media3 is the primary. libVLC (`LibVLCFragment`, `VlcPlayerAdapter`,
`player/VideoPlayerGlueVLC.java`) is the fallback for streams ExoPlayer refuses:
unusual codecs, RTSP/RTMP, malformed or non-standard manifests.

Selection in the original is largely static per source. Better: try ExoPlayer, and
on an unrecoverable *format* error (not a network error) retry the same option in
VLC. Distinguish the two — retrying a network failure in a second engine just
doubles the wait.

Cost: libVLC here is ~42 MB (arm64) plus ~38 MB (armeabi-v7a), the dominant term
in a 97 MB APK. If you ship it, use ABI splits or Play Feature Delivery so the
majority of users who never hit a VLC-only stream do not carry it.

The two hosts must expose the same interface to the rest of the app. The original
duplicates a lot between `PlaybackFragment` and `LibVLCFragment`; define a single
`PlayerHost` abstraction (`prepare/play/pause/release/trackSelection/state`) and
implement it twice.

---

## Cookie jar modes

`WebapiSingleton` offers three cookie policies, and the distinction matters for
resolvers:

- **Full** — persist and resend across requests. For multi-hop flows where a
  session cookie from hop 1 authorises hop 3.
- **Empty** — accept nothing, send nothing. For sources where a stale cookie
  causes a redirect loop or a geo-decision to stick.
- **Default/scoped** — per-host store, cleared per resolution.

Make this an explicit per-source setting rather than a global mode toggled before
each call. Scope the jar to one resolution attempt so a failed attempt cannot
poison the retry — a genuine bug class in the original's shared-singleton design.

---

## Subtitles

`player/MediaSourceHub.java` sideloads subtitles as a `SingleSampleMediaSource`,
either from a remote URL or from a local file served through a
`ByteArrayDataSource`. The useful part is its **encoding detection**: it tries
UTF-8, checks whether the decoded text contains Hebrew characters or otherwise
looks like valid UTF-8, and if not falls back to `windows-1255` then
`ISO-8859-8`, re-encoding to UTF-8.

Keep that logic for any non-Latin subtitle source — legacy `.srt` files in
regional encodings are common and render as mojibake without it. Media3 needs the
same treatment; the encoding heuristic is player-independent, so lift it into a
standalone `SubtitleDecoder` utility with unit tests over sample files.
