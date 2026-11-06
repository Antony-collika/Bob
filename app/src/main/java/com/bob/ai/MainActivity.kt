package com.bob.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bob.ai.ui.screens.ChatListScreen
import com.bob.ai.ui.screens.ChatScreen
import com.bob.ai.ui.screens.SearchScreen
import com.bob.ai.ui.screens.SettingsScreen
import com.bob.ai.ui.screens.ToolsScreen
import com.bob.ai.ui.theme.BobTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BobTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "list") {
                        composable("list") {
                            ChatListScreen(
                                onOpenChat = { id -> navController.navigate("chat/$id") },
                                onOpenSearch = { navController.navigate("search") },
                                onOpenTools = { navController.navigate("tools") },
                                onOpenSettings = { navController.navigate("settings") }
                            )
                        }
                        composable(
                            route = "chat/{conversationId}?scrollTo={messageId}",
                            arguments = listOf(
                                navArgument("conversationId") {
                                    type = NavType.StringType
                                },
                                navArgument("messageId") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                }
                            )
                        ) { backStackEntry ->
                            val conversationId =
                                backStackEntry.arguments?.getString("conversationId") ?: ""
                            val scrollTo = backStackEntry.arguments?.getString("messageId")
                            ChatScreen(
                                conversationId = conversationId,
                                scrollToMessageId = scrollTo,
                                onBack = { navController.popBackStack() },
                                onOpenConversation = { id ->
                                    navController.navigate("chat/$id")
                                }
                            )
                        }
                        composable("search") {
                            SearchScreen(
                                onBack = { navController.popBackStack() },
                                onOpenResult = { conversationId, messageId ->
                                    navController.navigate(
                                        "chat/$conversationId?scrollTo=$messageId"
                                    )
                                }
                            )
                        }
                        composable("tools") {
                            ToolsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
