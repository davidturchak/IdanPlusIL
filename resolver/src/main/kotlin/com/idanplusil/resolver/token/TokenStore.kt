package com.idanplusil.resolver.token

/**
 * Cache for short-lived entitlement tokens.
 *
 * The expiry is the point. The reference app caches tokens bare and discovers
 * staleness only when a segment 403s, leaning on the player to recover. Storing
 * [expiresAtMillis] lets us re-resolve proactively and treat the 403 path as a
 * safety net rather than the mechanism.
 */
interface TokenStore {
    fun get(key: String, nowMillis: Long): String?
    fun put(key: String, token: String, expiresAtMillis: Long)
    fun invalidate(key: String)

    companion object {
        /** Treat a token as expired this long before it actually is. */
        const val SKEW_MILLIS = 60_000L
    }
}

class InMemoryTokenStore : TokenStore {
    private data class Entry(val token: String, val expiresAtMillis: Long)

    private val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    override fun get(key: String, nowMillis: Long): String? {
        val e = map[key] ?: return null
        if (nowMillis >= e.expiresAtMillis - TokenStore.SKEW_MILLIS) {
            map.remove(key)
            return null
        }
        return e.token
    }

    override fun put(key: String, token: String, expiresAtMillis: Long) {
        map[key] = Entry(token, expiresAtMillis)
    }

    override fun invalidate(key: String) {
        map.remove(key)
    }
}
