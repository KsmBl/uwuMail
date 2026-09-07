package de.uwumail.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.core.ActionType
import de.uwumail.core.WizardLog
import de.uwumail.core.MatchMode
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.RuleActionEntity
import de.uwumail.data.db.RuleConditionEntity
import de.uwumail.data.db.RuleEntity
import de.uwumail.ui.common.folderLabel
import de.uwumail.di.AppContainer
import de.uwumail.rules.MatchContext
import de.uwumail.rules.RuleMatcher
import de.uwumail.rules.Suggestion
import de.uwumail.rules.SuggestionReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An action on its way into the rule. The words for it are built where the
 * strings are: an action carrying its own label was how a resource id ended up
 * being drawn as a number.
 */
data class PendingAction(val type: ActionType, val arg: String?)

data class WizardState(
    val analysing: Boolean = true,
    val samples: List<MessageEntity> = emptyList(),
    val report: SuggestionReport? = null,
    val selected: Set<Int> = emptySet(),
    val actions: List<PendingAction> = emptyList(),
    val name: String = "",
    val folders: List<FolderEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    /** Which mailboxes the rule runs on; null is all of them. */
    val accountId: Long? = null,
    val matchedOthers: Int = 0,
    /** Every message the picked conditions catch, for the sheet that lists them. */
    val matches: List<MatchedMail> = emptyList(),
    /** How much cached mail the count above was measured against. */
    val scanned: Int = 0,
    val saved: Boolean = false,
    val error: String? = null
) {
    val suggestions: List<Suggestion> get() = report?.suggestions.orEmpty()
    val canSave: Boolean get() = name.isNotBlank() && selected.isNotEmpty() && actions.isNotEmpty()

    /**
     * The folders the action menu offers. Scoping the rule to one account also
     * scopes what there is to browse: the rest are not where this rule's mail
     * is going to be.
     */
    fun foldersForScope(): List<FolderEntity> =
        folders.filter { accountId == null || it.accountId == accountId }
}

/**
 * Backs the "create a rule from these messages" flow.
 *
 * The point is that the user never has to write a regex: they pick messages,
 * the suggester finds what those messages share, and each candidate is scored
 * against the rest of the cached mailbox so the consequences are visible.
 */
class RuleWizardViewModel(
    private val container: AppContainer,
    private val messageIds: List<Long>
) : ViewModel() {

    private val _state = MutableStateFlow(WizardState())
    val state = _state.asStateFlow()

    /**
     * Match contexts for everything outside the selection, parsed once.
     * Re-deriving these on every checkbox tap would re-read and re-parse
     * thousands of rows for a single toggle.
     */
    private var otherContexts: List<MatchContext> = emptyList()

    /** The messages behind [otherContexts], in the same order, so a match can be shown. */
    private var otherMessages: List<MessageEntity> = emptyList()

    /** Folder id to the "[mailbox] Folder" the sheet lists a match under. */
    private var folderNames: Map<Long, String> = emptyMap()

    /** What the analysis is doing, for the watchdog to name. */
    @Volatile private var step: String = "starting"

    /** The thread the heavy half runs on, so its stack can be asked for. */
    @Volatile private var worker: Thread? = null

    init {
        viewModelScope.launch {
            WizardLog.begin("rule wizard, ${messageIds.size} messages selected")
            // Anything thrown in here used to leave the screen saying it was
            // still working, for ever. A mailbox large enough to run the
            // analysis out of memory is exactly the case that reaches it.
            val watchdog = launch { watch() }
            runCatching { analyse() }
                .onFailure { error ->
                    WizardLog.failure("analyse", error)
                    _state.update {
                        it.copy(analysing = false, error = error.message ?: error.toString())
                    }
                }
            watchdog.cancel()
            WizardLog.write("done, log at ${WizardLog.path()}")
        }
    }

    /**
     * Says, out loud and repeatedly, which step is taking so long — and where
     * the working thread actually is while it does. A spinner that never stops
     * tells nobody anything; this tells us the step and then the line.
     */
    private suspend fun watch() {
        var waited = 0L
        while (true) {
            delay(WATCHDOG_INTERVAL)
            waited += WATCHDOG_INTERVAL
            WizardLog.write("still in '$step' after ${waited / 1000}s")
            if (waited >= WATCHDOG_STACK_AFTER) WizardLog.stackOf(worker)
        }
    }

    private suspend fun analyse() {
        val samples = mark("reading the selected messages") {
            container.db.messageDao().getAll(messageIds)
        }
        WizardLog.write("read ${samples.size} of ${messageIds.size} selected messages")
        val folders = mark("reading folders") {
            container.db.folderDao().let { dao ->
                container.db.accountDao().getAll().flatMap { dao.forAccount(it.id) }
            }
        }
        val pathById = folders.associate { it.id to it.path }
        val accounts = mark("reading accounts") { container.db.accountDao().getAll() }
        // Which mailbox a match sits in is half of what makes the list worth
        // reading: the same folder name exists on every account.
        folderNames = folders.associate { it.id to folderLabel(it, accounts) }
        val corpus = mark("reading the corpus") {
            container.db.messageDao().recentForAnalysis(CORPUS_LIMIT)
        }
        WizardLog.write("corpus is ${corpus.size} messages (limit $CORPUS_LIMIT)")

        val report = withContext(Dispatchers.Default) {
            worker = Thread.currentThread()
            val selectedIds = samples.mapTo(HashSet()) { it.id }
            otherMessages = corpus.filter { it.id !in selectedIds }
            otherContexts = mark("building match contexts") {
                otherMessages.map { MatchContext.of(it, pathById[it.folderId].orEmpty()) }
            }
            WizardLog.write("built ${otherContexts.size} match contexts")
            mark("looking for what they share") {
                container.ruleSuggester.analyse(samples, corpus) {
                    pathById[it.folderId].orEmpty()
                }
            }
        }
        WizardLog.write(
            "report: ${report.suggestions.size} suggestions, " +
                "${report.recommended.size} recommended"
        )

        _state.update {
            it.copy(
                analysing = false,
                samples = samples,
                report = report,
                folders = folders,
                accounts = accounts,
                // The mail this was started from is the rule's subject, so its
                // account is the opening answer. It is only the opening one:
                // the same newsletter arrives at more than one address.
                accountId = samples.map { it.accountId }.distinct().singleOrNull(),
                selected = report.recommended.toSet(),
                name = report.suggestedRuleName.let {
                    container.appContext.getString(it.id, *it.args.toTypedArray())
                },
                matchedOthers = report.recommendedOthersMatched,
                scanned = corpus.size
            )
        }
        recount()
    }

    fun toggleSuggestion(index: Int) {
        _state.update { current ->
            val selected = if (index in current.selected) current.selected - index
            else current.selected + index
            current.copy(selected = selected)
        }
        recount()
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    /**
     * Narrows the rule to one mailbox, or widens it to all of them.
     *
     * The actions are left alone. A folder is remembered as its path, not as
     * one account's folder, so an action already picked still means something
     * on every account that has that path.
     */
    fun setAccountScope(accountId: Long?) = _state.update { it.copy(accountId = accountId) }

    fun addAction(type: ActionType, arg: String?) = _state.update {
        if (it.actions.any { existing -> existing.type == type && existing.arg == arg }) it
        else it.copy(actions = it.actions + PendingAction(type, arg))
    }

    fun removeAction(index: Int) = _state.update {
        it.copy(actions = it.actions.filterIndexed { i, _ -> i != index })
    }

    fun save(onSaved: (Long) -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            runCatching {
                val conditions = current.selected.sorted()
                    .map { current.suggestions[it].condition.copy(id = 0, ruleId = 0) }
                val actions = current.actions.mapIndexed { index, action ->
                    RuleActionEntity(
                        ruleId = 0,
                        type = action.type.name,
                        stringArg = action.arg,
                        orderIndex = index
                    )
                }
                container.db.ruleDao().replaceRule(
                    RuleEntity(
                        name = current.name.trim(),
                        enabled = true,
                        accountId = current.accountId,
                        matchMode = MatchMode.ALL.name,
                        createdAt = System.currentTimeMillis()
                    ),
                    conditions,
                    actions
                )
            }.onSuccess { id ->
                _state.update { it.copy(saved = true) }
                onSaved(id)
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    /** Recomputes how much extra mail the current selection would catch. */
    /**
     * Re-scores the picked conditions, and keeps what they caught.
     *
     * The list is gathered here rather than counted twice: the same pass that
     * answers "how many" can say which, and the sheet showing them has to be
     * able to open without waiting for the corpus to be walked again.
     */
    private fun recount() {
        viewModelScope.launch {
            val current = _state.value
            val conditions = current.selected.map { current.suggestions[it].condition }
            if (conditions.isEmpty()) {
                _state.update { it.copy(matchedOthers = 0, matches = emptyList()) }
                return@launch
            }
            val contexts = otherContexts
            val messages = otherMessages
            val caught = withContext(Dispatchers.Default) {
                contexts.indices.filter { i ->
                    conditions.all { RuleMatcher.matches(it, contexts[i]) }
                }.map { messages[it] }
            }
            // The messages the wizard was started from are caught by definition
            // and belong in the list, marked as the ones that were asked for.
            val samples = current.samples.map { describe(it, sample = true) }
            _state.update {
                it.copy(
                    matchedOthers = caught.size,
                    matches = samples + caught.map { m -> describe(m, sample = false) }
                )
            }
        }
    }

    /** Puts one message into the words the sheet lists it by. */
    private fun describe(message: MessageEntity, sample: Boolean) = MatchedMail(
        id = message.id,
        subject = message.subject,
        from = message.fromName?.takeIf { it.isNotBlank() } ?: message.fromAddress.orEmpty(),
        folder = folderNames[message.folderId].orEmpty(),
        receivedAt = message.receivedAt,
        isSample = sample
    )

    /** Names a step while it runs, and says how long it took when it ends. */
    private inline fun <T> mark(what: String, body: () -> T): T {
        step = what
        WizardLog.write("-> $what")
        val began = System.currentTimeMillis()
        val result = body()
        WizardLog.write("<- $what took ${System.currentTimeMillis() - began}ms")
        return result
    }

    private companion object {
        const val CORPUS_LIMIT = 3000
        const val WATCHDOG_INTERVAL = 2000L
        const val WATCHDOG_STACK_AFTER = 6000L
    }
}
