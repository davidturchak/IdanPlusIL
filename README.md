# IdanPlusIL

An Android TV live channel streaming app.

> **Status: early scaffold.** The repository currently holds the architecture
> reference and project setup. Application code has not landed yet.

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
Client                                        Backend
──────                                        ───────
ChannelCatalog   id, title, logo, epgId
      │
      │   GET /v1/channels/{id}/stream
      ├────────────────────────────────────►  StreamResolver
      │                                         ├── direct manifest
      │                                         ├── HTML + embedded JSON
      │                                         ├── iframe chase
      │                                         ├── platform API
      │                                         └── entitlement / token
      │   ◄──── {url, headers, cookies, expiresAt, options[]}
      │
Option picker    auto-select by priority, or present a source menu
      │
PlayerHost       Media3 (ExoPlayer)
```

Three patterns carry most of the weight:

- **Three-tier fallback.** A remote `force` flag bypasses the resolver entirely,
  then live resolution, then a static default. The flag is a server-side kill
  switch: when a source changes and a resolver breaks, you flip it in config and
  installed clients recover without an app release.
- **Resolvers never throw.** They degrade to a configured fallback. A channel
  playing a stale stream beats a channel showing an error.
- **Resolvers accumulate options rather than pick one**, so the selection layer
  and the player error policy both have somewhere to fall back to.

### Settled decisions

**Stream resolution runs server-side.** The client calls
`GET /v1/channels/{id}/stream` and receives `{url, headers, cookies, expiresAt,
options[]}`. It does no scraping, no HTML parsing, and holds no provider
credentials. Resolver fixes ship in minutes instead of waiting on an app release
and user updates, and one implementation serves every platform. The client keeps
the catalog, the option picker, and the player. An on-device fallback path stays
for when the backend is unreachable.

**The client ships Media3 only.** No second player engine. A stream Media3 cannot
play is a backend sourcing problem, not a reason to bundle another decoder — that
choice keeps tens of megabytes of native libraries out of the APK. If a fallback
engine ever becomes unavoidable, it goes behind a feature module rather than into
the base APK.

## Repository layout

```
.claude/skills/live-tv-streaming/   architecture reference (loads automatically
                                    when working on playback in this repo)
LICENSE                             MIT
README.md
```

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

Secrets belong on a server, never in the client — client-side obfuscation of API
keys does not survive contact with a decompiler. Local credential files
(`secrets.properties`, `.env`, keystores) are gitignored; keep them that way.

## License

[MIT](LICENSE) © 2026 David Turchak
