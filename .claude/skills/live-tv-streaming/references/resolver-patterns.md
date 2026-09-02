# Stream resolution techniques

Five technique classes cover every live channel in the analysed app. When adding
a channel to a new app, identify which class its source falls into and reuse the
matching resolver skeleton rather than writing bespoke code per channel.

Ordered by cost and fragility, cheapest and most durable first.

---

## 1. Direct manifest

The channel's stream URL is stable enough to ship in config. No resolution at all
— the catalog or remote config URL goes straight to the player.

Used for most secondary channels in the original (parliament, community,
news-aggregator channels), all wired as plain `videoUrl(...)` values in the
catalog around `Utils.java:700-800`.

```kotlin
class DirectResolver(private val url: String) : StreamResolver {
    override suspend fun resolve(channel: Channel) =
        listOf(StreamOption(url, "default", 100))
}
```

**Prefer this whenever it works.** Half the "scrapers" in the original exist
because nobody rechecked whether the direct URL still worked.

---

## 2. HTML page + embedded JSON

The broadcaster's web player ships its configuration in a `<script
type="application/json">` block. Fetch the page, extract the block, read the
stream URL out of the parsed JSON.

Reference: `Channel11AsyncTask.get11resource()`.

Flow:
1. `GET` the channel's player page with realistic browser headers.
2. Lookbehind/lookahead regex to isolate the JSON block by its `id` attribute.
3. Parse with Gson/kotlinx-serialization, walk to the source field.
4. Optionally repeat for sibling variants (the original fetches page id 2 for the
   main feed and id 23 for the closed-captions feed, and returns both).

```kotlin
val html = client.get(pageUrl).body()
val json = Regex("""(?s)(?<=<script id="$blockId" type="application/json">).*?(?=</script>)""")
    .find(html)?.value ?: throw ResolveFailed("json block missing")
val src = Json.parseToJsonElement(json)
    .jsonObject["content"]?.jsonObject?.get("src")?.jsonPrimitive?.content
```

**Fragility:** the `id` attribute and the JSON path are the break points. Put both
in remote config so a site change is a config push, not a release. Extract the
block with an HTML parser (jsoup) selecting on `script#id` rather than regex —
regex over HTML is what forces the `(?s)` lookbehind gymnastics in the original.

---

## 3. Iframe chase

The page does not contain the stream; it contains an `<iframe>` pointing at a
player host that does. Fetch the outer page, extract the iframe `src`, fetch
that, then scan for any `.m3u8`.

Reference: `Channel14AsyncTask` (two hops, takes the last `.m3u8` found) and
`Channel23AsyncTask` (three hops, final URL sits in a `"UrlRedirector":"..."`
JSON field inside the player page).

```kotlin
val outer  = client.get(channel.pageUrl).body()
val iframe = Regex("""<iframe[^>]+src="([^"]+)"""").find(outer)?.groupValues?.get(1)
    ?: throw ResolveFailed("no iframe")
val inner  = client.get(iframe.absoluteAgainst(channel.pageUrl)).body()
val m3u8   = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").findAll(inner)
    .map { it.value }.lastOrNull() ?: throw ResolveFailed("no manifest")
```

Notes:
- Send `Referer: <outer page>` on the iframe request; player hosts commonly check it.
- Resolve relative iframe `src` against the outer URL. The original uses
  `Patterns.WEB_URL` and silently mishandles relative URLs.
- "Last match wins" is a heuristic — the highest-quality variant tends to appear
  last. Prefer collecting all matches as `StreamOption`s over guessing.
- Cap the hop count (2-3) so a redirect loop cannot hang resolution.

---

## 4. Platform API (Kaltura and similar OVPs)

The broadcaster uses an off-the-shelf online video platform with a documented
API. Instead of scraping, call the API.

Reference: `Channel13AsyncTask` — a single Kaltura `multirequest` POST to
`/api_v3/service/multirequest` batching four calls, using Kaltura's
`{n:result:field}` back-reference syntax so the session key from call 1 feeds
calls 2-4:

1. `session.startWidgetSession` with the publisher's widget id → session key
2. `baseEntry.list` filtered by entry id → resolves the live entry
3. `baseEntry.getPlaybackContext` → returns `sources[]`
4. `metadata_metadata.list` → auxiliary metadata

Then iterate `sources[].url`.

```kotlin
val sources = kaltura.multirequest(partnerId, widgetId, entryId)
    .flatMap { it.sources }
    .map { StreamOption(it.url, it.format ?: "src", priorityFor(it)) }
```

**This is the most durable non-direct technique** — OVP APIs are versioned and
change far less often than page markup. When a channel uses Kaltura, Brightcove,
JW Player, THEO, or similar, always prefer the API over scraping the page that
embeds it. Identify the platform from the player page's script hosts.

The original hardcodes the partner/widget/entry ids inline. Move them to config —
they change when the broadcaster reprovisions.

---

## 5. Entitlement / tokenised CDN

The manifest URL is public but rejects unauthenticated requests; a separate
entitlements service issues a short-lived token appended to the URL as a query
string.

Reference: `Channel12AsyncTask` (commercial broadcaster → Akamai token). Flow:

1. `GET` a params endpoint with `type`/`device`/`strto` query args → JSON
   containing a media GUID and a channel id.
2. `GET` a media endpoint with those ids → array of media entries, each with a
   `format`, a `url`, and a `cdn`.
3. Select an entry by format preference (primary format prefix, else a named
   fallback format).
4. Build the entitlements URL from the selected entry's `url` + `cdn`.
5. `GET` it → response carries a `caseId` and a `tickets[]` array; on success
   `tickets[0]` has `url` and `ticket`.
6. Append the ticket to the manifest URL as a query string (`?` or `&` depending
   on whether the URL already has a query).
7. Cache the ticket.

```kotlin
val params  = api.liveParams(type, device)
val media   = api.mediaList(params.guid, params.channelId)
val entry   = media.firstOrNull { it.format.startsWith(primaryFormat) }
           ?: media.first { it.format == fallbackFormat }
val ticket  = api.entitlement(entry.url, entry.cdn)
    .takeIf { it.caseId == "1" }?.tickets?.first()
    ?: throw ResolveFailed("entitlement denied")
val url = entry.url.withQuery(ticket.ticket)
```

Implementation notes:
- Normalise protocol-relative URLs (`//host/path` → `https://host/path`). The
  original only handles the `http:` case.
- The token is the credential for **every segment**, not just the manifest —
  ExoPlayer appends it automatically for relative segment URIs, but verify with
  absolute-URI playlists.
- Store the token with an expiry (see SKILL.md). The original stores it bare.
- If the entitlement response denies access, that is the answer — surface it as
  "unavailable" rather than retrying against other CDN paths.

---

## Choosing a technique for a new channel

```
Does a stable public manifest URL exist?           → 1. Direct
Is the player from a known OVP (Kaltura/Brightcove/JW/THEO)? → 4. Platform API
Does the page embed its config as JSON?            → 2. HTML + JSON
Does the page delegate to an iframe player host?   → 3. Iframe chase
Does the manifest 403 without a token?             → 5. Entitlement
```

Check in that order. Techniques 2 and 3 are last resorts — they are the ones that
break, and they are why the original needs a per-channel kill switch.

## Shared resolver hygiene

- **Timeout every hop** (5-8 s). A resolver that hangs is worse than one that fails.
- **Instrument break points.** Log which stage failed (`fetch` / `extract` /
  `parse` / `entitlement`) so a broken channel is diagnosable from telemetry
  without a repro.
- **Test resolvers offline** against saved HTML/JSON fixtures. Every technique
  above is a pure function from response bodies to `StreamOption`s — keep the
  parsing separable from the fetching so it is unit-testable.
- **Never let a resolver run on the main thread**, and never let two resolvers
  share mutable HTTP config (see the singleton warning in SKILL.md).
