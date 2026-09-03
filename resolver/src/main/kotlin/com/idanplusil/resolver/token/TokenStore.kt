package com.idanplusil.resolver.token

/** A cached entitlement, with the expiry it was issued under. */
data class CachedToken(
    val token: String,
    val expiresAtMillis: Long,
    /** The URL the issuer granted the token for, when it named one. */
    val grantedUrl: String? = null,
)

/**
 * Cache for short-lived entitlement tokens.
 *
 * The expiry is the point. The reference app caches tokens bare and discovers
 * staleness only when a segment 403s, leaning on the player to recover. Storing
 * the expiry lets us re-resolve proactively and treat the 403 path as a safety
 * net rather than the mechanism - and when that net does catch a 403, the
 * caller drops the cached token with [invalidatePrefix] so the re-resolve mints
 * a fresh one instead of handing back the ticket the CDN just rejected.
 */
interface TokenStore {
    fun get(key: String, nowMillis: Long): CachedToken?
    fun put(key: String, token: CachedToken)
    fun invalidate(key: String)
    fun invalidatePrefix(prefix: String)

    companion object {
        /** Treat a token as expired this long before it actually is. */
        const val SKEW_MILLIS = 60_000L
    }
}

class InMemoryTokenStore : TokenStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, CachedToken>()

    override fun get(key: String, nowMillis: Long): CachedToken? {
        val e = map[key] ?: return null
        if (nowMillis >= e.expiresAtMillis - TokenStore.SKEW_MILLIS) {
            map.remove(key)
            return null
        }
        return e
    }

    override fun put(key: String, token: CachedToken) {
        map[key] = token
    }

    override fun invalidate(key: String) {
        map.remove(key)
    }

    override fun invalidatePrefix(prefix: String) {
        map.keys.removeIf { it.startsWith(prefix) }
    }
}
