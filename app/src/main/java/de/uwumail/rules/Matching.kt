package de.uwumail.rules

import de.uwumail.core.Json
import de.uwumail.core.RuleField
import de.uwumail.core.RuleOperator
import de.uwumail.core.toAddressList
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.RuleConditionEntity
import java.util.concurrent.ConcurrentHashMap

/** Everything a rule is allowed to look at, extracted once per message. */
data class MatchContext(
    val accountId: Long,
    val folderPath: String,
    val fromAddress: String,
    val fromName: String,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val body: String,
    val headers: Map<String, List<String>>,
    val attachmentNames: List<String>,
    val sizeBytes: Long
) {
    companion object {
        fun of(
            message: MessageEntity,
            folderPath: String,
            attachmentNames: List<String> = emptyList(),
            bodyOverride: String? = null
        ): MatchContext {
            val body = bodyOverride
                ?: message.bodyPlain
                ?: message.bodyHtml?.let { de.uwumail.mail.MimeUtil.htmlToText(it) }
                ?: message.preview
            return MatchContext(
                accountId = message.accountId,
                folderPath = folderPath,
                fromAddress = message.fromAddress.orEmpty(),
                fromName = message.fromName.orEmpty(),
                to = message.toList.toAddressList(),
                cc = message.ccList.toAddressList(),
                subject = message.subject,
                body = body,
                headers = Json.decodeHeaders(message.headersJson),
                attachmentNames = attachmentNames,
                sizeBytes = message.sizeBytes
            )
        }
    }
}

object RuleMatcher {

    private val regexCache = ConcurrentHashMap<String, Regex>()

    fun matches(condition: RuleConditionEntity, ctx: MatchContext): Boolean {
        val field = runCatching { RuleField.valueOf(condition.field) }.getOrNull() ?: return false
        val operator = runCatching { RuleOperator.valueOf(condition.operator) }.getOrNull() ?: return false
        val hit = when (field) {
            RuleField.SIZE_BYTES -> compareNumeric(operator, ctx.sizeBytes, condition.value)
            else -> actualValues(field, condition, ctx).any { compare(operator, it, condition) }
        }
        return hit != condition.negate
    }

    fun matchesAll(
        conditions: List<RuleConditionEntity>,
        ctx: MatchContext,
        requireAll: Boolean
    ): Boolean {
        if (conditions.isEmpty()) return false
        return if (requireAll) conditions.all { matches(it, ctx) }
        else conditions.any { matches(it, ctx) }
    }

    /**
     * What the message actually holds in the field a condition tests.
     *
     * Public because the answer to "why did this rule not catch it" is almost
     * always this value sitting next to the one the condition was looking for.
     * A list because several fields hold more than one — every recipient, every
     * value of a repeated header, every attachment name.
     */
    fun actualValues(
        field: RuleField,
        condition: RuleConditionEntity,
        ctx: MatchContext
    ): List<String> = when (field) {
        RuleField.FROM -> listOf(ctx.fromAddress)
        RuleField.FROM_NAME -> listOf(ctx.fromName)
        RuleField.TO -> ctx.to
        RuleField.CC -> ctx.cc
        RuleField.TO_OR_CC -> ctx.to + ctx.cc
        RuleField.SUBJECT -> listOf(ctx.subject)
        RuleField.BODY -> listOf(ctx.body)
        RuleField.HEADER -> ctx.headers[condition.headerName?.lowercase().orEmpty()].orEmpty()
        RuleField.LIST_ID -> ctx.headers["list-id"].orEmpty()
        RuleField.ATTACHMENT_NAME -> ctx.attachmentNames
        RuleField.FOLDER -> listOf(ctx.folderPath)
        RuleField.SIZE_BYTES -> listOf(ctx.sizeBytes.toString())
    }

    private fun compare(
        operator: RuleOperator,
        subject: String,
        condition: RuleConditionEntity
    ): Boolean {
        val ignoreCase = !condition.caseSensitive
        val needle = condition.value
        return when (operator) {
            RuleOperator.REGEX -> compileOrNull(needle, ignoreCase)?.containsMatchIn(subject) ?: false
            RuleOperator.CONTAINS -> subject.contains(needle, ignoreCase)
            RuleOperator.EQUALS -> subject.equals(needle, ignoreCase)
            RuleOperator.STARTS_WITH -> subject.startsWith(needle, ignoreCase)
            RuleOperator.ENDS_WITH -> subject.endsWith(needle, ignoreCase)
            RuleOperator.DOMAIN_IS -> subject.substringAfterLast('@').equals(needle, ignoreCase)
            RuleOperator.GREATER_THAN, RuleOperator.LESS_THAN ->
                compareNumeric(operator, subject.toLongOrNull() ?: return false, needle)
        }
    }

    private fun compareNumeric(operator: RuleOperator, actual: Long, raw: String): Boolean {
        val expected = raw.trim().toLongOrNull() ?: return false
        return when (operator) {
            RuleOperator.GREATER_THAN -> actual > expected
            RuleOperator.LESS_THAN -> actual < expected
            RuleOperator.EQUALS -> actual == expected
            else -> false
        }
    }

    /** Compiles and caches; an invalid pattern simply never matches instead of crashing sync. */
    fun compileOrNull(pattern: String, ignoreCase: Boolean): Regex? {
        val key = (if (ignoreCase) "i:" else "s:") + pattern
        regexCache[key]?.let { return it }
        return runCatching {
            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            Regex(pattern, options)
        }.getOrNull()?.also { regexCache[key] = it }
    }

    fun isValidRegex(pattern: String): Boolean = runCatching { Regex(pattern) }.isSuccess
}
