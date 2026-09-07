package de.uwumail.core

import androidx.annotation.StringRes
import de.uwumail.R

/** Transport security for IMAP/SMTP connections. */
enum class Security { SSL_TLS, STARTTLS, NONE }

/** Well-known roles a folder can play, used for archive/trash/sent routing. */
enum class FolderType { INBOX, ARCHIVE, SENT, DRAFTS, TRASH, SPAM, CUSTOM, LOCAL }

/** Part of a message a rule condition looks at. */
enum class RuleField(@StringRes val label: Int) {
    FROM(R.string.field_from),
    FROM_NAME(R.string.field_from_name),
    TO(R.string.field_to),
    CC(R.string.field_cc),
    TO_OR_CC(R.string.field_to_or_cc),
    SUBJECT(R.string.field_subject),
    BODY(R.string.field_body),
    HEADER(R.string.field_header),
    LIST_ID(R.string.field_list_id),
    ATTACHMENT_NAME(R.string.field_attachment_name),
    SIZE_BYTES(R.string.field_size),
    FOLDER(R.string.field_folder);

    /** Body matching forces a full body fetch during sync, so we track it. */
    val needsBody: Boolean get() = this == BODY

    /**
     * Attachment names are only known once the message structure has been
     * read, which the envelope does not carry — so this forces the same fetch
     * [needsBody] does. Without it the condition matched against nothing and
     * quietly never fired.
     */
    val needsAttachmentNames: Boolean get() = this == ATTACHMENT_NAME
}

/** How a condition's value is compared against the extracted field text. */
enum class RuleOperator(@StringRes val label: Int) {
    REGEX(R.string.op_regex),
    CONTAINS(R.string.op_contains),
    EQUALS(R.string.op_equals),
    STARTS_WITH(R.string.op_starts_with),
    ENDS_WITH(R.string.op_ends_with),
    DOMAIN_IS(R.string.op_domain_is),
    GREATER_THAN(R.string.op_greater_than),
    LESS_THAN(R.string.op_less_than)
}

/** Conditions inside one rule are combined with this. */
enum class MatchMode { ALL, ANY }

/**
 * What a rule does when it matches.
 *
 * [needsTargetFolder] marks the actions whose `stringArg` carries a folder path
 * (remote IMAP path, or a local folder name for [MOVE_TO_LOCAL]).
 */
enum class ActionType(@StringRes val label: Int, val needsTargetFolder: Boolean = false) {
    MARK_READ(R.string.action_mark_read),
    MARK_UNREAD(R.string.action_mark_unread),
    FLAG(R.string.action_flag),
    UNFLAG(R.string.action_unflag),
    ARCHIVE(R.string.action_archive),
    MOVE_TO_TRASH(R.string.action_move_to_trash),
    DELETE_PERMANENTLY(R.string.action_delete_permanently),
    MOVE_TO_FOLDER(R.string.action_move_to_folder, true),
    COPY_TO_FOLDER(R.string.action_copy_to_folder, true),
    MOVE_TO_LOCAL(R.string.action_move_to_local, true),
    COPY_TO_LOCAL(R.string.action_copy_to_local, true),
    DOWNLOAD(R.string.action_download),
    SUPPRESS_NOTIFICATION(R.string.action_suppress_notification),
    NOTIFY_SILENT(R.string.action_notify_silent),
    NOTIFY_HIGH(R.string.action_notify_high);

    /** Actions that take the message off the server side of the current folder. */
    val removesFromFolder: Boolean
        get() = this == ARCHIVE || this == MOVE_TO_TRASH || this == DELETE_PERMANENTLY ||
                this == MOVE_TO_FOLDER || this == MOVE_TO_LOCAL
}

/**
 * What a swipe across a message in the list does.
 *
 * Each direction is configured separately, and [NONE] is how a direction is
 * turned off — a swipe that does nothing simply does not start.
 */
enum class SwipeAction(@StringRes val label: Int) {
    NONE(R.string.swipe_none),
    SELECT(R.string.swipe_select),
    TOGGLE_READ(R.string.swipe_toggle_read),
    TOGGLE_STAR(R.string.swipe_toggle_star),
    ARCHIVE(R.string.swipe_archive),
    TRASH(R.string.swipe_trash),
    MOVE(R.string.swipe_move),
    DELETE(R.string.swipe_delete);

    /**
     * Whether the row is on its way out, so the swipe can carry it off the
     * screen instead of springing back. A move springs back: the folder picker
     * has still to be answered, and it can be dismissed.
     */
    val carriesRowAway: Boolean
        get() = this == ARCHIVE || this == TRASH || this == DELETE

    /** A swipe is easy to do by accident, and this one cannot be undone. */
    val needsConfirmation: Boolean get() = this == DELETE

    /**
     * What this swipe actually does to a row already in the bin.
     *
     * Trashing a message that is in the trash has nowhere to move it to and
     * does nothing at all, so the gesture becomes the one it was reaching for.
     * It arrives as [DELETE], which already asks first and already draws itself
     * in red — the swipe says what it is about to do before it is let go of.
     */
    fun inBin(isInBin: Boolean): SwipeAction =
        if (isInBin && this == TRASH) DELETE else this

    /**
     * Whether this still makes sense once messages are being picked out.
     * Selecting more of them does; archiving one of them, while others are
     * selected and untouched, does not.
     */
    val worksWhileSelecting: Boolean get() = this == SELECT || this == NONE

    companion object {
        /** Tolerates a name that is no longer known, rather than losing the setting. */
        fun of(name: String?): SwipeAction =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** Which colours the app wears. */
enum class AppTheme(@StringRes val label: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
    MOCHA(R.string.theme_mocha);

    /** Whether this theme is a dark one, for everything outside Compose. */
    val isDark: Boolean get() = this == DARK || this == MOCHA;

    companion object {
        /** Tolerates a name that is no longer known, rather than losing the setting. */
        fun of(name: String?): AppTheme = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
