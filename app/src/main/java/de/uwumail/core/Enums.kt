package de.uwumail.core

/** Transport security for IMAP/SMTP connections. */
enum class Security { SSL_TLS, STARTTLS, NONE }

/** Well-known roles a folder can play, used for archive/trash/sent routing. */
enum class FolderType { INBOX, ARCHIVE, SENT, DRAFTS, TRASH, SPAM, CUSTOM, LOCAL }

/** Part of a message a rule condition looks at. */
enum class RuleField(val label: String) {
    FROM("From address"),
    FROM_NAME("From display name"),
    TO("To"),
    CC("Cc"),
    TO_OR_CC("To or Cc"),
    SUBJECT("Subject"),
    BODY("Body text"),
    HEADER("Raw header"),
    LIST_ID("List-Id"),
    ATTACHMENT_NAME("Attachment filename"),
    SIZE_BYTES("Size in bytes"),
    FOLDER("Folder path");

    /** Body matching forces a full body fetch during sync, so we track it. */
    val needsBody: Boolean get() = this == BODY
}

/** How a condition's value is compared against the extracted field text. */
enum class RuleOperator(val label: String) {
    REGEX("matches regex"),
    CONTAINS("contains"),
    EQUALS("is exactly"),
    STARTS_WITH("starts with"),
    ENDS_WITH("ends with"),
    DOMAIN_IS("domain is"),
    GREATER_THAN("is greater than"),
    LESS_THAN("is less than")
}

/** Conditions inside one rule are combined with this. */
enum class MatchMode { ALL, ANY }

/**
 * What a rule does when it matches.
 *
 * [needsTargetFolder] marks the actions whose `stringArg` carries a folder path
 * (remote IMAP path, or a local folder name for [MOVE_TO_LOCAL]).
 */
enum class ActionType(val label: String, val needsTargetFolder: Boolean = false) {
    MARK_READ("Mark as read"),
    MARK_UNREAD("Mark as unread"),
    FLAG("Star"),
    UNFLAG("Unstar"),
    ARCHIVE("Archive"),
    MOVE_TO_TRASH("Move to trash"),
    DELETE_PERMANENTLY("Delete permanently"),
    MOVE_TO_FOLDER("Move to folder", true),
    COPY_TO_FOLDER("Copy to folder", true),
    MOVE_TO_LOCAL("Move to local folder", true),
    COPY_TO_LOCAL("Copy to local folder", true),
    DOWNLOAD("Download full message"),
    SUPPRESS_NOTIFICATION("Don't notify"),
    NOTIFY_SILENT("Notify silently"),
    NOTIFY_HIGH("Notify with high priority");

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
enum class SwipeAction(val label: String) {
    NONE("Nothing"),
    TOGGLE_READ("Mark read / unread"),
    TOGGLE_STAR("Star / unstar"),
    ARCHIVE("Archive"),
    TRASH("Move to trash"),
    MOVE("Move to folder…"),
    DELETE("Delete permanently");

    /**
     * Whether the row is on its way out, so the swipe can carry it off the
     * screen instead of springing back. A move springs back: the folder picker
     * has still to be answered, and it can be dismissed.
     */
    val carriesRowAway: Boolean
        get() = this == ARCHIVE || this == TRASH || this == DELETE

    /** A swipe is easy to do by accident, and this one cannot be undone. */
    val needsConfirmation: Boolean get() = this == DELETE

    companion object {
        /** Tolerates a name that is no longer known, rather than losing the setting. */
        fun of(name: String?): SwipeAction =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** Which colours the app wears. */
enum class AppTheme(val label: String) {
    SYSTEM("Follow the system"),
    LIGHT("Light"),
    DARK("Dark"),
    MOCHA("Catppuccin Mocha");

    companion object {
        /** Tolerates a name that is no longer known, rather than losing the setting. */
        fun of(name: String?): AppTheme = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
