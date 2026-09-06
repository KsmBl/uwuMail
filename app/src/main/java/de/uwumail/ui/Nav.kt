package de.uwumail.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.uwumail.ui.accounts.AccountSetupScreen
import de.uwumail.ui.accounts.AccountsScreen
import de.uwumail.ui.compose.ComposeScreen
import de.uwumail.ui.folders.FoldersScreen
import de.uwumail.ui.mail.MailScreen
import de.uwumail.ui.mail.MessageScreen
import de.uwumail.ui.rules.RuleEditScreen
import de.uwumail.ui.rules.RuleWizardScreen
import de.uwumail.ui.rules.RulesScreen
import de.uwumail.ui.settings.SettingsScreen

object Routes {
    const val MAIL = "mail"
    const val ACCOUNTS = "accounts"
    const val SETUP = "setup"
    const val MESSAGE = "message"
    const val COMPOSE = "compose"
    const val FOLDERS = "folders"
    const val RULES = "rules"
    const val RULE_EDIT = "ruleEdit"
    const val WIZARD = "wizard"
    const val SETTINGS = "settings"
}

@Composable
fun UwuMailNavHost(
    navController: NavHostController = rememberNavController(),
    startOnAccounts: Boolean,
    openMessageId: Long? = null,
    mailtoUri: String? = null
) {
    LaunchedEffect(openMessageId) {
        openMessageId?.takeIf { it > 0 }?.let {
            navController.navigate("${Routes.MESSAGE}/$it")
        }
    }
    LaunchedEffect(mailtoUri) {
        mailtoUri?.let {
            navController.navigate(
                "${Routes.COMPOSE}?accountId=0&reply=0&replyAll=false&forward=0&draft=0&mailto=" +
                    java.net.URLEncoder.encode(it, "UTF-8")
            )
        }
    }

    // Last resort. Nothing should be able to empty the back stack now, but an
    // app that cannot be recovered without force-stopping it is a bad way to
    // find out otherwise: an empty host draws nothing and answers nothing.
    val current by navController.currentBackStackEntryAsState()
    var everArrived by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(current) {
        if (current != null) {
            everArrived = true
        } else if (everArrived) {
            navController.navigate(Routes.MAIL) { launchSingleTop = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (startOnAccounts) Routes.ACCOUNTS else Routes.MAIL
    ) {
        composable(Routes.MAIL) { entry ->
            MailScreen(
                // A draft is unfinished writing, so it opens in the composer.
                onOpenMessage = { navController.go(entry, "${Routes.MESSAGE}/$it") },
                onOpenDraft = { id ->
                    navController.go(
                        entry,
                        "${Routes.COMPOSE}?accountId=0&reply=0&replyAll=false" +
                            "&forward=0&draft=$id"
                    )
                },
                onCompose = { accountId ->
                    navController.go(
                        entry,
                        "${Routes.COMPOSE}?accountId=${accountId ?: 0}&reply=0&replyAll=false&forward=0&draft=0"
                    )
                },
                onManageRules = { navController.go(entry, Routes.RULES) },
                onManageFolders = { navController.go(entry, "${Routes.FOLDERS}/$it") },
                onManageAccounts = { navController.go(entry, Routes.ACCOUNTS) },
                onSettings = { navController.go(entry, Routes.SETTINGS) },
                onCreateRuleFrom = { ids ->
                    if (ids.isNotEmpty()) {
                        navController.go(entry, "${Routes.WIZARD}/${ids.joinToString(",")}")
                    }
                }
            )
        }

        composable(Routes.ACCOUNTS) { entry ->
            AccountsScreen(
                onBack = { navController.leave(entry) },
                onAdd = { navController.go(entry, "${Routes.SETUP}?accountId=0") },
                onEdit = { navController.go(entry, "${Routes.SETUP}?accountId=$it") }
            )
        }

        composable(
            "${Routes.SETUP}?accountId={accountId}",
            arguments = listOf(navArgument("accountId") {
                type = NavType.LongType; defaultValue = 0L
            })
        ) { entry ->
            AccountSetupScreen(
                accountId = entry.arguments?.getLong("accountId") ?: 0L,
                onDone = { navController.leave(entry) }
            )
        }

        composable(
            "${Routes.MESSAGE}/{messageId}",
            arguments = listOf(navArgument("messageId") { type = NavType.LongType })
        ) { entry ->
            val messageId = entry.arguments?.getLong("messageId") ?: 0L
            MessageScreen(
                messageId = messageId,
                onBack = { navController.leave(entry) },
                onReply = { id, all ->
                    navController.go(
                        entry,
                        "${Routes.COMPOSE}?accountId=0&reply=$id&replyAll=$all&forward=0&draft=0"
                    )
                },
                onForward = { id ->
                    navController.go(
                        entry,
                        "${Routes.COMPOSE}?accountId=0&reply=0&replyAll=false&forward=$id&draft=0"
                    )
                },
                // Swiping between messages replaces this one rather than
                // stacking, so back always returns to the list rather than
                // walking every message that was swiped through.
                onOpenMessage = { id ->
                    if (entry.isInFront()) {
                        navController.navigate("${Routes.MESSAGE}/$id") {
                            popUpTo("${Routes.MESSAGE}/{messageId}") { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(
            "${Routes.COMPOSE}?accountId={accountId}&reply={reply}&replyAll={replyAll}" +
                "&forward={forward}&draft={draft}&mailto={mailto}",
            arguments = listOf(
                navArgument("accountId") { type = NavType.LongType; defaultValue = 0L },
                navArgument("reply") { type = NavType.LongType; defaultValue = 0L },
                navArgument("replyAll") { type = NavType.BoolType; defaultValue = false },
                navArgument("forward") { type = NavType.LongType; defaultValue = 0L },
                navArgument("draft") { type = NavType.LongType; defaultValue = 0L },
                navArgument("mailto") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                }
            )
        ) { entry ->
            val args = entry.arguments
            ComposeScreen(
                accountId = args?.getLong("accountId") ?: 0L,
                replyToMessageId = args?.getLong("reply") ?: 0L,
                replyAll = args?.getBoolean("replyAll") ?: false,
                forwardMessageId = args?.getLong("forward") ?: 0L,
                draftMessageId = args?.getLong("draft") ?: 0L,
                mailto = args?.getString("mailto")?.let {
                    runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                },
                onDone = { navController.leave(entry) }
            )
        }

        composable(
            "${Routes.FOLDERS}/{accountId}",
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) { entry ->
            FoldersScreen(
                accountId = entry.arguments?.getLong("accountId") ?: 0L,
                onBack = { navController.leave(entry) }
            )
        }

        composable(Routes.RULES) { entry ->
            RulesScreen(
                onBack = { navController.leave(entry) },
                onEdit = { navController.go(entry, "${Routes.RULE_EDIT}?ruleId=$it") },
                onCreate = { navController.go(entry, "${Routes.RULE_EDIT}?ruleId=0") }
            )
        }

        composable(
            "${Routes.RULE_EDIT}?ruleId={ruleId}",
            arguments = listOf(navArgument("ruleId") { type = NavType.LongType; defaultValue = 0L })
        ) { entry ->
            RuleEditScreen(
                ruleId = entry.arguments?.getLong("ruleId") ?: 0L,
                onBack = { navController.leave(entry) }
            )
        }

        composable(
            "${Routes.WIZARD}/{messageIds}",
            arguments = listOf(navArgument("messageIds") { type = NavType.StringType })
        ) { entry ->
            val ids = entry.arguments?.getString("messageIds").orEmpty()
                .split(',').mapNotNull { it.toLongOrNull() }
            RuleWizardScreen(
                messageIds = ids,
                onBack = { navController.leave(entry) },
                onSaved = { ruleId ->
                    if (entry.isInFront()) {
                        navController.popBackStack()
                        navController.navigate("${Routes.RULE_EDIT}?ruleId=$ruleId")
                    }
                }
            )
        }

        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(onBack = { navController.leave(entry) })
        }
    }
}
