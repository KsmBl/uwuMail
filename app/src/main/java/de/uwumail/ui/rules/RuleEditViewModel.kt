package de.uwumail.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.core.ActionType
import de.uwumail.core.MatchMode
import de.uwumail.core.RuleField
import de.uwumail.core.RuleOperator
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.RuleActionEntity
import de.uwumail.data.db.RuleConditionEntity
import de.uwumail.data.db.RuleEntity
import de.uwumail.di.AppContainer
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
    val accountId: Long? = null,
    val folderPath: String? = null,
    val matchMode: MatchMode = MatchMode.ALL,
    val stopProcessing: Boolean = false,
    val conditions: List<RuleConditionEntity> = emptyList(),
    val actions: List<RuleActionEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val previewCount: Int? = null,
    val previewTotal: Int = 0,
    val previewing: Boolean = false,
    val message: String? = null,
    val saved: Boolean = false
) {
    val canSave: Boolean
        get() = name.isNotBlank() && conditions.isNotEmpty() && actions.isNotEmpty()

    fun foldersForScope(): List<FolderEntity> =
        folders.filter { accountId == null || it.accountId == accountId }
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
                            accountId = entry.rule.accountId,
                            folderPath = entry.rule.folderPath,
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
            val result = withContext(Dispatchers.Default) {
                val corpus = container.db.messageDao().recentForAnalysis(CORPUS_LIMIT)
                val paths = current.folders.associate { it.id to it.path }
                val matched = corpus.count { message ->
                    if (current.accountId != null && message.accountId != current.accountId) return@count false
                    val path = paths[message.folderId].orEmpty()
                    if (current.folderPath != null && !current.folderPath.equals(path, true)) return@count false
                    RuleMatcher.matchesAll(
                        current.conditions.filter { it.value.isNotBlank() },
                        MatchContext.of(message, path),
                        current.matchMode == MatchMode.ALL
                    )
                }
                matched to corpus.size
            }
            _state.update {
                it.copy(previewing = false, previewCount = result.first, previewTotal = result.second)
            }
        }
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            runCatching {
                container.db.ruleDao().replaceRule(
                    RuleEntity(
                        id = current.ruleId,
                        name = current.name.trim(),
                        enabled = current.enabled,
                        priority = current.priority.toIntOrNull() ?: 100,
                        accountId = current.accountId,
                        folderPath = current.folderPath,
                        matchMode = current.matchMode.name,
                        stopProcessing = current.stopProcessing,
                        createdAt = System.currentTimeMillis()
                    ),
                    current.conditions.filter { it.value.isNotBlank() },
                    current.actions
                )
            }.onSuccess {
                _state.update { it.copy(saved = true) }
            }.onFailure { e ->
                _state.update { it.copy(message = e.message) }
            }
        }
    }

    /** Runs the saved rule set over mail that is already in the cache. */
    fun applyToExistingMail() {
        viewModelScope.launch {
            val folders = _state.value.foldersForScope().filter { !it.isLocal }
            var total = 0
            folders.forEach { folder ->
                runCatching { container.syncManager.applyRulesToFolder(folder.id) }
                    .onSuccess { total += it }
            }
            _state.update { it.copy(message = "Rules applied to $total existing message(s)") }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val CORPUS_LIMIT = 3000
    }
}
