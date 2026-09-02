package com.idanplusil.tv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Treats an expired token as something to recover from, not something to fail
 * on.
 *
 * A tokenised CDN answers 403 (sometimes 410) on every segment once the ticket
 * lapses. Excluding that location briefly and asking the app to re-resolve is
 * the single highest-value error rule in a live player - and it is what the
 * reference app leans on implicitly, having never stored a token expiry at all.
 */
@UnstableApi
class LiveLoadErrorPolicy(
    private val onNeedsReresolve: () -> Unit,
) : DefaultLoadErrorHandlingPolicy() {

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        if (loadErrorInfo.exception.isTokenExpiry()) {
            onNeedsReresolve()
            if (fallbackOptions.numberOfLocations > 1) {
                return LoadErrorHandlingPolicy.FallbackSelection(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION,
                    EXCLUSION_MS,
                )
            }
        }
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 4

    private fun Throwable.isTokenExpiry(): Boolean =
        this is HttpDataSource.InvalidResponseCodeException &&
            (responseCode == 403 || responseCode == 410)

    private companion object {
        const val EXCLUSION_MS = 60_000L
    }
}
