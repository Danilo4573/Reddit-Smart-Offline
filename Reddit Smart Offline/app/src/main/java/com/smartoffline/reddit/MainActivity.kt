package com.smartoffline.reddit

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartoffline.reddit.ui.AppIcons
import com.smartoffline.reddit.ui.AppTheme
import com.smartoffline.reddit.ui.AppViewModel
import com.smartoffline.reddit.ui.Screen
import com.smartoffline.reddit.ui.Tabs
import com.smartoffline.reddit.ui.screens.AboutScreen
import com.smartoffline.reddit.ui.screens.AddSubredditScreen
import com.smartoffline.reddit.ui.screens.DownloadsScreen
import com.smartoffline.reddit.ui.screens.HomeScreen
import com.smartoffline.reddit.ui.screens.MediaViewerScreen
import com.smartoffline.reddit.ui.screens.PostScreen
import com.smartoffline.reddit.ui.screens.SearchScreen
import com.smartoffline.reddit.ui.screens.SettingsScreen
import com.smartoffline.reddit.ui.screens.StorageScreen
import com.smartoffline.reddit.ui.screens.SubredditConfigScreen
import com.smartoffline.reddit.ui.screens.SubredditsScreen
import com.smartoffline.reddit.ui.screens.SyncScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            AppTheme(prefs.theme) { Root(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (data != null && data.scheme == "redditsmartoffline") {
            vm.handleAuthRedirect(data)
            vm.selectTab(Screen.Settings)
            return
        }
        when (intent?.getStringExtra("open")) {
            "sync" -> { vm.selectTab(Screen.Home); vm.navigate(Screen.SyncDashboard) }
            "storage" -> { vm.selectTab(Screen.Settings); vm.navigate(Screen.StorageDashboard) }
            "settings" -> vm.selectTab(Screen.Settings)
            "home" -> vm.selectTab(Screen.Home)
        }
    }
}

@Composable
private fun Root(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.currentHostState(it) } }
    val current = vm.stack.lastOrNull() ?: Screen.Home
    val isTab = current in Tabs
    BackHandler(enabled = vm.stack.size > 1 || current != Screen.Home) { vm.back() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (isTab) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                val items = listOf(
                    Triple(Screen.Home, "Home", Icons.Filled.Home),
                    Triple(Screen.Subreddits, "Subreddits", Icons.Filled.List),
                    Triple(Screen.Downloads, "Downloads", AppIcons.Download),
                    Triple(Screen.Search, "Search", Icons.Filled.Search),
                    Triple(Screen.Settings, "Settings", Icons.Filled.Settings),
                )
                items.forEach { (screen, label, icon) ->
                    NavigationBarItem(
                        selected = current == screen,
                        onClick = { vm.selectTab(screen) },
                        icon = { Icon(icon, label) },
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = if (isTab) padding.calculateBottomPadding() else androidx.compose.ui.unit.Dp(0f))) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    if (targetState in Tabs && initialState in Tabs) fadeIn() togetherWith fadeOut()
                    else if (vm.stack.contains(initialState)) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
                    else fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                },
                label = "screens",
            ) { screen ->
                when (screen) {
                    Screen.Home -> HomeScreen(vm)
                    Screen.Subreddits -> SubredditsScreen(vm)
                    Screen.Downloads -> DownloadsScreen(vm, null)
                    Screen.Search -> SearchScreen(vm)
                    Screen.Settings -> SettingsScreen(vm)
                    is Screen.SubredditConfig -> SubredditConfigScreen(vm, screen.name)
                    is Screen.SubredditPosts -> DownloadsScreen(vm, screen.name)
                    is Screen.PostView -> PostScreen(vm, screen.id)
                    is Screen.MediaView -> MediaViewerScreen(vm, screen.postId, screen.index)
                    Screen.SyncDashboard -> SyncScreen(vm)
                    Screen.StorageDashboard -> StorageScreen(vm)
                    Screen.AddSubreddit -> AddSubredditScreen(vm)
                    Screen.About -> AboutScreen(vm)
                }
            }
        }
    }
}

private suspend fun SnackbarHostState.currentHostState(msg: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(msg)
}
