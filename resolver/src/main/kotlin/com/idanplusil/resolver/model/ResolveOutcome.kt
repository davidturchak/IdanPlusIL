package com.idanplusil.resolver.model

/** Where a resolution attempt broke. Recorded so a dead resolver is diagnosable. */
enum class Stage { FETCH, EXTRACT, PARSE, ENTITLEMENT, TIMEOUT }

sealed interface ResolveOutcome {
    data class Ok(val options: List<StreamOption>) : ResolveOutcome
    data class Failed(val stage: Stage, val message: String? = null) : ResolveOutcome

    val optionsOrEmpty: List<StreamOption>
        get() = (this as? Ok)?.options.orEmpty()
}
