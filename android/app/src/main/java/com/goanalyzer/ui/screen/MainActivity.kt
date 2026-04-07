package com.goanalyzer.ui.screen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.goanalyzer.GoAnalyzerApp
import com.goanalyzer.data.GameRecord
import com.goanalyzer.data.GameRecordRepository
import com.goanalyzer.data.GoAnalyzerContainer
import com.goanalyzer.data.ThemeManager
import com.goanalyzer.ui.theme.GoAnalyzerTheme

class MainActivity : ComponentActivity() {

    private lateinit var container: GoAnalyzerContainer
    private lateinit var themeManager: ThemeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as GoAnalyzerApp).container
        themeManager = ThemeManager(this)

        enableEdgeToEdge()
        setContent {
            GoAnalyzerTheme(themeManager = themeManager) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GoAnalyzerNavHost(container, themeManager)
                }
            }
        }
    }
}

@Composable
fun GoAnalyzerNavHost(container: GoAnalyzerContainer, themeManager: ThemeManager) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "analysis") {
        composable("analysis") {
            AnalysisScreen(
                container = container,
                navController = navController,
                onSettingsClick = { navController.navigate("settings") },
                onHistoryClick = { navController.navigate("history") }
            )
        }
        composable("settings") {
            SettingsScreen(
                container = container,
                themeManager = themeManager,
                onBack = { navController.popBackStack() }
            )
        }
        composable("history") {
            GameHistoryScreen(
                viewModel = GameHistoryViewModel(container.gameRecordRepository),
                onRecordClick = { record ->
                    // 导航回分析页面并加载记录
                    navController.previousBackStackEntry?.savedStateHandle?.set("game_record_id", record.id)
                    navController.popBackStack("analysis", inclusive = false)
                },
                onBack = { navController.popBackStack() }
            )
        }
}
}
