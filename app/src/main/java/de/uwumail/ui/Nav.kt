package de.uwumail.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
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
                "${Routes.COMPOSE}?accountId=0&reply=0&replyAll=false&forward=0&mailto=" +
                    java.net.URLEncoder.encode(it, "UTF-8")
            )
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (startOnAccounts) Routes.ACCOUNTS else Routes.MAIL
    ) {
        composable(Routes.MAIL) {
            MailScreen(
                onOpenMessage = { navController.navigate("${Routes.MESSAGE}/$it") },
                onCompose = { accountId ->
                    navController.navigate(
                        "${Routes.COMPOSE}?accountId=${accountId ?: 0}&reply=0&replyAll=false&forward=0"
                    )
                },
                onManageRules = { navController.navigate(Routes.RULES) },
                onManageFolders = { navController.navigate("${Routes.FOLDERS}/$it") },
                onManageAccounts = { navController.navigate(Routes.ACCOUNTS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onCreateRuleFrom = { ids ->
                    if (ids.isNotEmpty()) {
                        navController.navigate("${Routes.WIZARD}/${ids.joinToString(",")}")
                    }
                }
            )
        }

        composable(Routes.ACCOUNTS) {
            AccountsScreen(
                onBack = {
                    if (!navController.popBackStack()) navController.navigate(Routes.MAIL)
                },
                onAdd = { navController.navigate("${Routes.SETUP}?accountId=0") },
                onEdit = { navController.navigate("${Routes.SETUP}?accountId=$it") }
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
                onDone = {
                    if (!navController.popBackStack()) navController.navigate(Routes.MAIL)
                }
            )
        }

        composable(
            "${Routes.MESSAGE}/{messageId}",
            arguments = listOf(navArgument("messageId") { type = NavType.LongType })
        ) { entry ->
            val messageId = entry.arguments?.getLong("messageId") ?: 0L
            MessageScreen(
                messageId = messageId,
                onBack = { navController.popBackStack() },
                onReply = { id, all ->
                    navController.navigate(
                        "${Routes.COMPOSE}?accountId=0&reply=$id&replyAll=$all&forward=0"
                    )
                },
                onForward = { id ->
                    navController.navigate(
                        "${Routes.COMPOSE}?accountId=0&reply=0&replyAll=false&forward=$id"
                    )
                }
            )
        }

        composable(
            "${Routes.COMPOSE}?accountId={accountId}&reply={reply}&replyAll={replyAll}" +
                "&forward={forward}&mailto={mailto}",
            arguments = listOf(
                navArgument("accountId") { type = NavType.LongType; defaultValue = 0L },
                navArgument("reply") { type = NavType.LongType; defaultValue = 0L },
                navArgument("replyAll") { type = NavType.BoolType; defaultValue = false },
                navArgument("forward") { type = NavType.LongType; defaultValue = 0L },
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
                mailto = args?.getString("mailto")?.let {
                    runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                },
                onDone = { navController.popBackStack() }
            )
        }

        composable(
            "${Routes.FOLDERS}/{accountId}",
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) { entry ->
            FoldersScreen(
                accountId = entry.arguments?.getLong("accountId") ?: 0L,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.RULES) {
            RulesScreen(
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate("${Routes.RULE_EDIT}?ruleId=$it") },
                onCreate = { navController.navigate("${Routes.RULE_EDIT}?ruleId=0") }
            )
        }

        composable(
            "${Routes.RULE_EDIT}?ruleId={ruleId}",
            arguments = listOf(navArgument("ruleId") { type = NavType.LongType; defaultValue = 0L })
        ) { entry ->
            RuleEditScreen(
                ruleId = entry.arguments?.getLong("ruleId") ?: 0L,
                onBack = { navController.popBackStack() }
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
                onBack = { navController.popBackStack() },
                onSaved = { ruleId ->
                    navController.popBackStack()
                    navController.navigate("${Routes.RULE_EDIT}?ruleId=$ruleId")
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
