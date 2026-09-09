package de.uwumail.data.repo

import de.uwumail.data.db.AppDatabase
import de.uwumail.data.db.RuleActionEntity
import de.uwumail.data.db.RuleConditionEntity
import de.uwumail.data.db.RuleEntity
import de.uwumail.data.settings.AppSettings
import de.uwumail.data.settings.SettingsStore
import org.json.JSONArray
import org.json.JSONObject

/** What a restore found in a file, before anything is written. */
data class BackupContents(val rules: Int, val hasSettings: Boolean)

/**
 * Writes and reads the parts of uwuMail worth keeping.
 *
 * Rules are hand-built regex that exist nowhere else — not on the mail server,
 * not in any account — so a lost phone loses them for good. Accounts and
 * passwords are deliberately **not** included: the credentials are sealed to
 * this device's keystore and could not be restored anywhere else even if they
 * were exported, and a file full of mail passwords is not a thing to leave in
 * a Downloads folder.
 */
class BackupRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore
) {

    suspend fun export(): String {
        val rules = JSONArray()
        for (entry in db.ruleDao().allRules()) {
            val conditions = JSONArray()
            entry.conditions.forEach { condition ->
                conditions.put(
                    JSONObject()
                        .put("field", condition.field)
                        .put("headerName", condition.headerName)
                        .put("operator", condition.operator)
                        .put("value", condition.value)
                        .put("caseSensitive", condition.caseSensitive)
                        .put("negate", condition.negate)
                )
            }
            val actions = JSONArray()
            entry.actions.sortedBy { it.orderIndex }.forEach { action ->
                actions.put(
                    JSONObject()
                        .put("type", action.type)
                        .put("stringArg", action.stringArg)
                )
            }
            rules.put(
                JSONObject()
                    .put("name", entry.rule.name)
                    .put("enabled", entry.rule.enabled)
                    .put("priority", entry.rule.priority)
                    // Account ids are local to a device, so a rule scoped to
                    // one is restored unscoped rather than to the wrong mailbox.
                    .put("folderPath", entry.rule.folderPath)
                    .put("matchMode", entry.rule.matchMode)
                    .put("stopProcessing", entry.rule.stopProcessing)
                    .put("conditions", conditions)
                    .put("actions", actions)
            )
        }

        val current = settings.current
        val preferences = JSONObject()
            .put("unsubscribeBanner", current.unsubscribeBanner)
            .put("askStripTracking", current.askStripTracking)
            .put("preloadUnread", current.preloadUnread)
            .put("blockRemoteImages", current.blockRemoteImages)
            .put("allowJavaScript", current.allowJavaScript)
            .put("filterTinyImages", current.filterTinyImages)
            .put("minImageWidth", current.minImageWidth)
            .put("minImageHeight", current.minImageHeight)
            .put("theme", current.theme.name)
            .put("swipeRight", current.swipeRight.name)
            .put("swipeLeft", current.swipeLeft.name)
            .put("swipeThresholdPercent", current.swipeThresholdPercent)
            .put("groupIntoConversations", current.groupIntoConversations)
            .put("readerTextZoom", current.readerTextZoom)
            .put("syncWindowEnabled", current.syncWindowEnabled)
            .put("syncDays", JSONArray(current.syncDays.toList()))
            .put("syncStartMinutes", current.syncStartMinutes)
            .put("syncEndMinutes", current.syncEndMinutes)

        return JSONObject()
            .put("format", FORMAT)
            .put("exportedAt", System.currentTimeMillis())
            .put("rules", rules)
            .put("settings", preferences)
            .toString(2)
    }

    /**
     * Adds the rules from a backup and applies its settings.
     *
     * Rules are added rather than replacing what is there: a restore that
     * silently deleted the rules someone had written since would be a worse
     * failure than a duplicate.
     */
    suspend fun import(json: String): BackupContents? {
        val contents = inspect(json) ?: return null
        val root = JSONObject(json)

        val rules = root.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until rules.length()) {
            val entry = rules.optJSONObject(i) ?: continue
            val rule = RuleEntity(
                name = entry.optString("name").ifBlank { "Imported rule" },
                enabled = entry.optBoolean("enabled", true),
                priority = entry.optInt("priority", 100),
                // Account ids are local to a device; see the note where it is written.
                accountIds = null,
                folderPath = entry.optString("folderPath").takeIf { it.isNotBlank() },
                matchMode = entry.optString("matchMode").ifBlank { "ALL" },
                stopProcessing = entry.optBoolean("stopProcessing", false),
                createdAt = System.currentTimeMillis()
            )
            val conditions = entry.optJSONArray("conditions") ?: JSONArray()
            val actions = entry.optJSONArray("actions") ?: JSONArray()
            db.ruleDao().replaceRule(
                rule,
                (0 until conditions.length()).mapNotNull { index ->
                    val c = conditions.optJSONObject(index) ?: return@mapNotNull null
                    RuleConditionEntity(
                        ruleId = 0,
                        field = c.optString("field"),
                        headerName = c.optString("headerName").takeIf { it.isNotBlank() },
                        operator = c.optString("operator"),
                        value = c.optString("value"),
                        caseSensitive = c.optBoolean("caseSensitive", false),
                        negate = c.optBoolean("negate", false)
                    )
                },
                (0 until actions.length()).mapNotNull { index ->
                    val a = actions.optJSONObject(index) ?: return@mapNotNull null
                    RuleActionEntity(
                        ruleId = 0,
                        type = a.optString("type"),
                        stringArg = a.optString("stringArg").takeIf { it.isNotBlank() }
                    )
                }
            )
        }

        root.optJSONObject("settings")?.let { preferences ->
            settings.update { it.applying(preferences) }
        }
        return contents
    }

    private fun AppSettings.applying(json: JSONObject) = copy(
        unsubscribeBanner = json.optBoolean("unsubscribeBanner", unsubscribeBanner),
        askStripTracking = json.optBoolean("askStripTracking", askStripTracking),
        preloadUnread = json.optBoolean("preloadUnread", preloadUnread),
        blockRemoteImages = json.optBoolean("blockRemoteImages", blockRemoteImages),
        allowJavaScript = json.optBoolean("allowJavaScript", allowJavaScript),
        filterTinyImages = json.optBoolean("filterTinyImages", filterTinyImages),
        minImageWidth = json.optInt("minImageWidth", minImageWidth),
        minImageHeight = json.optInt("minImageHeight", minImageHeight),
        theme = de.uwumail.core.AppTheme.of(json.optString("theme")),
        swipeRight = de.uwumail.core.SwipeAction.of(json.optString("swipeRight")),
        swipeLeft = de.uwumail.core.SwipeAction.of(json.optString("swipeLeft")),
        // Held to the allowed range: a backup from a later version, or one
        // edited by hand, must not restore a hair-trigger swipe.
        swipeThresholdPercent = AppSettings.clampSwipePercent(
            json.optInt("swipeThresholdPercent", swipeThresholdPercent)
        ),
        groupIntoConversations = json.optBoolean(
            "groupIntoConversations", groupIntoConversations
        ),
        readerTextZoom = AppSettings.clampTextZoom(
            json.optInt("readerTextZoom", readerTextZoom)
        ),
        syncWindowEnabled = json.optBoolean("syncWindowEnabled", syncWindowEnabled),
        syncDays = json.optJSONArray("syncDays")?.let { days ->
            (0 until days.length()).map { days.optInt(it) }.toSet()
        }?.takeIf { it.isNotEmpty() } ?: syncDays,
        syncStartMinutes = json.optInt("syncStartMinutes", syncStartMinutes),
        syncEndMinutes = json.optInt("syncEndMinutes", syncEndMinutes)
    )

    companion object {
        /** Bumped when the shape changes; an unknown format is refused, not guessed at. */
        const val FORMAT = 1
        const val FILE_NAME = "uwumail-backup.json"

        /**
         * Reads a backup without writing anything, so a file can be judged
         * before it is trusted. Needs nothing from the database or settings,
         * which is what lets it be the same code the import path relies on.
         */
        fun inspect(json: String): BackupContents? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (root.optInt("format", 0) != FORMAT) return null
            return BackupContents(
                rules = root.optJSONArray("rules")?.length() ?: 0,
                hasSettings = root.optJSONObject("settings") != null
            )
        }
    }
}
