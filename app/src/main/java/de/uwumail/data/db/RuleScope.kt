package de.uwumail.data.db

/**
 * Which folders a rule is limited to.
 *
 * A rule used to be scoped to one folder or to all of them, which is the wrong
 * shape for the commonest wish: run this over the inbox and the spam folder,
 * and nowhere else. The paths live newline-separated in the single column that
 * already held one, because IMAP forbids CR and LF in a mailbox name — so no
 * path can contain the separator, and every rule written before this is still
 * one path with nothing to convert.
 *
 * Empty means every folder, which is the same thing null in the column has
 * always meant.
 */
val RuleEntity.folderPaths: List<String>
    get() = folderPath?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/** Whether this rule may run in [path]; a rule limited to nothing runs everywhere. */
fun RuleEntity.appliesToFolder(path: String): Boolean {
    val scope = folderPaths
    return scope.isEmpty() || scope.any { it.equals(path, true) }
}

/** The column value for a set of chosen folders; none chosen means every folder. */
fun folderScopeOf(paths: Collection<String>): String? = paths
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .takeIf { it.isNotEmpty() }
    ?.joinToString("\n")

/**
 * The accounts this rule is limited to, or empty for every account.
 *
 * Stored the same way the folders are, in one text column, so the two halves
 * of *Applies to* are the same shape and neither needs a table of its own.
 * Anything unreadable is dropped rather than becoming an account id of zero,
 * which would silently scope a rule to nothing.
 */
val RuleEntity.accountIdsIn: List<Long>
    get() = accountIds?.split('\n')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()

/** Whether this rule may run on [accountId]; a rule limited to nothing runs on all of them. */
fun RuleEntity.appliesToAccount(accountId: Long): Boolean {
    val scope = accountIdsIn
    return scope.isEmpty() || accountId in scope
}

/** The column value for a set of chosen accounts; none chosen means every account. */
fun accountScopeOf(ids: Collection<Long>): String? = ids
    .distinct()
    .takeIf { it.isNotEmpty() }
    ?.joinToString("\n")
