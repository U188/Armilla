package io.github.mangi.eta.agent.skill

/**
 * Decides which skills remain authorized for an already-started run.
 *
 * Lookup failures must keep the previous snapshot. Only a confirmed missing
 * assistant or a confirmed disable/uninstall may shrink the set.
 */
internal object SkillRunAuthorization {
    sealed interface Decision {
        data object KeepPrevious : Decision
        data object RevokeAll : Decision
        data class Restrict(val enabled: Set<String>, val installed: Set<String>?) : Decision
    }

    fun decide(
        repositoryReady: Boolean,
        profileLookupFailed: Boolean,
        profileEnabledIds: Set<String>?,
        installedLookupFailed: Boolean,
        installedIds: Set<String>?,
    ): Decision {
        if (!repositoryReady || profileLookupFailed) return Decision.KeepPrevious
        val enabled = profileEnabledIds ?: return Decision.RevokeAll
        if (installedLookupFailed) return Decision.Restrict(enabled, installed = null)
        return Decision.Restrict(enabled, installed = installedIds)
    }

    fun <T> apply(
        snapshot: List<T>,
        idOf: (T) -> String,
        stillPresent: (T) -> Boolean,
        decision: Decision,
        previous: List<T>,
    ): Pair<List<T>, Boolean> = when (decision) {
        Decision.KeepPrevious -> previous.filter(stillPresent).ifEmpty { snapshot.filter(stillPresent) } to false
        Decision.RevokeAll -> emptyList<T>() to true
        is Decision.Restrict -> snapshot.filter { entry ->
            val id = idOf(entry)
            id in decision.enabled &&
                (decision.installed == null || id in decision.installed) &&
                stillPresent(entry)
        } to true
    }
}
