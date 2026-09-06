package de.uwumail.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

/**
 * An empty database in memory, plus the few rows every DAO test needs before
 * it can insert a message: folders belong to an account and messages belong to
 * a folder, and the foreign keys are real.
 */
object TestDatabase {

    fun open(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppDatabase::class.java
    ).allowMainThreadQueries().build()

    fun account(email: String = "me@example.com") = AccountEntity(
        displayName = email.substringBefore('@'),
        email = email,
        color = 0,
        imapHost = "imap.example.com",
        imapPort = 993,
        imapSecurity = "SSL_TLS",
        imapUsername = email,
        smtpHost = "smtp.example.com",
        smtpPort = 465,
        smtpSecurity = "SSL_TLS",
        smtpUsername = email
    )
}
