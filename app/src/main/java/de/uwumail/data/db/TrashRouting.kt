package de.uwumail.data.db

import de.uwumail.core.FolderType

/**
 * Whether this folder is where [account] puts deleted mail.
 *
 * Asked of the account's own routing as well as the folder's type, because
 * either can be the only one that knows: a server with no SPECIAL-USE
 * attributes leaves every folder unclassified and only the account setting
 * says which is the bin, while a folder the server calls `\Trash` is plainly
 * the bin whether or not anyone has wired it up.
 *
 * A device folder is never the bin, whatever it is called. Its mail exists
 * nowhere else, so deleting from it permanently is the only copy going, and it
 * is not what the account trashes to in any case.
 */
fun FolderEntity.isBinFor(account: AccountEntity?): Boolean {
    if (isLocal) return false
    return path == account?.trashFolder || type == FolderType.TRASH.name
}
