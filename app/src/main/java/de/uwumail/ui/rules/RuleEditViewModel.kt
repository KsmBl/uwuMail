package de.uwumail.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.R
import de.uwumail.core.ActionType
import de.uwumail.core.MatchMode
import de.uwumail.core.RuleField
import de.uwumail.core.RuleOperator
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.RuleActionEntity
import de.uwumail.data.db.RuleConditionEntity
import de.uwumail.data.db.RuleEntity
import de.uwumail.data.db.accountIdsIn
import de.uwumail.data.db.accountScopeOf
import de.uwumail.data.db.folderPaths
import de.uwumail.data.db.folderScopeOf
import de.uwumail.di.AppContainer
import de.uwumail.ui.common.folderLabel
import de.uwumail.rules.MatchContext
import de.uwumail.rules.RuleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RuleEditState(
    val ruleId: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val priority: String = "100",
    /** The accounts the rule is limited to; empty means every account. */
    val accountIds: Set<Long> = emptySet(),
    /** The folders the rule is limited to; empty means every folder. */
    val folderPaths: Set<String> = emptySet(),
    val matchMode: MatchMode = MatchMode.ALL,
    val stopProcessing: Boolean = false,
    val conditions: List<RuleConditionEntity> = emptyList(),
    val actions: List<RuleActionEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val previewCount: Int? = null,
    /** What the draft rule caught, for the sheet that lists it. */
    val matches: List<MatchedMail> = emptyList(),
    val previewTotal: Int = 0,
    val previewing: Boolean = false,
    /** Replaying rules over cached mail; can take a while on a big folder. */
    val applying: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val saved: Boolean = false
) {
    val canSave: Boolean
        get() = name.isNotBlank() && conditions.isNotEmpty() && actions.isNotEmpty()

    fun foldersForScope(): List<FolderEntity> =
        folders.filter { accountIds.isEmpty() || it.accountId in accountIds }

    /** True when this rule may run on [id]; nothing ticked means every account. */
    fun coversAccount(id: Long): Boolean = accountIds.isEmpty() || id in accountIds

    /** True when this rule may run in [path]; nothing ticked means everywhere. */
    fun coversFolder(path: String): Boolean =
        folderPaths.isEmpty() || folderPaths.any { it.equals(path, true) }
}

class RuleEditViewModel(
    private val container: AppContainer,
    private val ruleId: Long
) : ViewModel() {

    private val _state = MutableStateFlow(RuleEditState(ruleId = ruleId))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val accounts = container.db.accountDao().getAll()
            val folders = accounts.flatMap { container.db.folderDao().forAccount(it.id) }
            _state.update { it.copy(accounts = accounts, folders = folders) }

            if (ruleId > 0) {
                container.db.ruleDao().get(ruleId)?.let { entry ->
                    _state.update {
                        it.copy(
                            name = entry.rule.name,
                            enabled = entry.rule.enabled,
                            priority = entry.rule.priority.toString(),
                            accountIds = entry.rule.accountIdsIn.toSet(),
                            folderPaths = entry.rule.folderPaths.toSet(),
                            matchMode = runCatching { MatchMode.valueOf(entry.rule.matchMode) }
                                .getOrDefault(MatchMode.ALL),
                            stopProcessing = entry.rule.stopProcessing,
                            conditions = entry.conditions,
                            actions = entry.actions.sortedBy { a -> a.orderIndex }
                        )
                    }
                }
            }
        }
    }

    /** Pre-populates a brand new rule, used by the "create from selection" wizard. */
    fun seed(name: String, conditions: List<RuleConditionEntity>, actions: List<RuleActionEntity>) {
        _state.update {
            it.copy(name = name, conditions = conditions, actions = actions)
        }
    }

    fun update(transform: (RuleEditState) -> RuleEditState) = _state.update(transform)

    /** Ticks or unticks one folder in the rule's scope. */
    fun toggleFolder(path: String) = _state.update { current ->
        current.copy(
            folderPaths = if (path in current.folderPaths) current.folderPaths - path
            else current.folderPaths + path
        )
    }

    /** Ticks or unticks one account in the rule's scope. */
    fun toggleAccount(id: Long) = _state.update { current ->
        val next = if (id in current.accountIds) current.accountIds - id
        else current.accountIds + id
        // The folders belong to the accounts, so narrowing which accounts the
        // rule covers has to let go of folders that are no longer among them.
        val reachable = current.folders
            .filter { next.isEmpty() || it.accountId in next }
            .map { it.path }
            .toSet()
        current.copy(accountIds = next, folderPaths = current.folderPaths intersect reachable)
    }

    /** Ticks or unticks a whole group at once, for the unified rows. */
    fun setFolders(paths: Collection<String>, on: Boolean) = _state.update { current ->
        current.copy(
            folderPaths = if (on) current.folderPaths + paths else current.folderPaths - paths.toSet()
        )
    }

    /** Back to every folder, which is what no folder ticked means. */
    fun clearFolders() = _state.update { it.copy(folderPaths = emptySet()) }

    fun addCondition() = _state.update {
        it.copy(
            conditions = it.conditions + RuleConditionEntity(
                ruleId = ruleId,
                field = RuleField.SUBJECT.name,
                operator = RuleOperator.CONTAINS.name,
                value = ""
            )
        )
    }

    fun updateCondition(index: Int, condition: RuleConditionEntity) = _state.update { current ->
        current.copy(
            conditions = current.conditions.toMutableList().also { it[index] = condition }
        )
    }

    fun removeCondition(index: Int) = _state.update { current ->
        current.copy(conditions = current.conditions.filterIndexed { i, _ -> i != index })
    }

    fun addAction(type: ActionType, arg: String?) = _state.update {
        it.copy(
            actions = it.actions + RuleActionEntity(
                ruleId = ruleId,
                type = type.name,
                stringArg = arg,
                orderIndex = it.actions.size
            )
        )
    }

    fun removeAction(index: Int) = _state.update { current ->
        current.copy(actions = current.actions.filterIndexed { i, _ -> i != index })
    }

    /**
     * Counts how many cached messages this draft would hit, so the blast radius
     * is visible before the rule is allowed to touch anything.
     */
    fun preview() {
        _state.update { it.copy(previewing = true) }
        viewModelScope.launch {
            val current = _state.value
            val attachmentRows = container.db.attachmentDao().fileNames()
            val result = withContext(Dispatchers.Default) {
                val corpus = container.db.messageDao().recentForAnalysis(CORPUS_LIMIT)
                val paths = current.folders.associate { it.id to it.path }
                val folderNames = current.folders
                    .associate { it.id to folderLabel(it, current.accounts) }
                // So a condition on an attachment name is scored against the
                // names actually held rather than against nothing at all.
                val attachmentNames = attachmentRows.groupBy({ it.messageId }, { it.fileName })
                val matched = corpus.filter { message ->
                    if (!current.coversAccount(message.accountId)) return@filter false
                    val path = paths[message.folderId].orEmpty()
                    if (!current.coversFolder(path)) return@filter false
                    RuleMatcher.matchesAll(
                        current.conditions.filter { it.value.isNotBlank() },
                        MatchContext.of(message, path, attachmentNames[message.id].orEmpty()),
                        current.matchMode == MatchMode.ALL
                    )
                }
                // Kept as well as counted: the same pass that says how many can
                // say which, and a count on its own is a number to be trusted
                // rather than a thing to be checked.
                Triple(
                    matched.size,
                    corpus.size,
                    matched.map { message ->
                        MatchedMail(
                            id = message.id,
                            subject = message.subject,
                            from = message.fromName?.takeIf { it.isNotBlank() }
                                ?: message.fromAddress.orEmpty(),
                            folder = folderNames[message.folderId].orEmpty(),
                            receivedAt = message.receivedAt
                        )
                    }
                )
            }
            _state.update {
                it.copy(
                    previewing = false,
                    previewCount = result.first,
                    previewTotal = result.second,
                    matches = result.third
                )
            }
        }
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                container.db.ruleDao().replaceRule(
                    RuleEntity(
                        id = current.ruleId,
                        name = current.name.trim(),
                        enabled = current.enabled,
                        priority = current.priority.toIntOrNull() ?: 100,
                        accountIds = accountScopeOf(current.accountIds),
                        folderPath = folderScopeOf(current.folderPaths),
                        matchMode = current.matchMode.name,
                        stopProcessing = current.stopProcessing,
                        createdAt = System.currentTimeMillis()
                    ),
                    current.conditions.filter { it.value.isNotBlank() },
                    current.actions
                )
            }.onSuccess {
                _state.update { it.copy(saving = false, saved = true) }
            }.onFailure { e ->
                _state.update { it.copy(saving = false, message = e.message) }
            }
        }
    }

    /** Runs the saved rule set over mail that is already in the cache. */
    fun applyToExistingMail() {
        _state.update { it.copy(applying = true) }
        viewModelScope.launch {
            val folders = _state.value.foldersForScope().filter { !it.isLocal }
            var total = 0
            folders.forEach { folder ->
                runCatching { container.syncManager.applyRulesToFolder(folder.id) }
                    .onSuccess { total += it }
            }
            _state.update {
                it.copy(
                    applying = false,
                    message = container.appContext.resources.getQuantityString(
                        R.plurals.rules_applied, total, total
                    )
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val CORPUS_LIMIT = 3000
    }
}
