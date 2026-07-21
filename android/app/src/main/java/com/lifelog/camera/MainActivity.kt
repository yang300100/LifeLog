package com.lifelog.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.lifelog.camera.ble.BleSyncService
import com.lifelog.camera.ui.components.BookmarkDateBar
import com.lifelog.camera.ui.components.FloatingCapsuleNav
import com.lifelog.camera.ui.components.diaryNavItems
import com.lifelog.camera.ui.components.rpgNavItems
import com.lifelog.camera.ui.theme.LifeLogTheme
import com.lifelog.camera.ui.timeline.TimelineScreen
import com.lifelog.camera.ui.report.ReportScreen
import com.lifelog.camera.ui.settings.SettingsScreen
import com.lifelog.camera.ui.settings.LogViewerScreen
import com.lifelog.camera.ui.realtime.RealtimeCharacterPage
import com.lifelog.camera.ui.realtime.RealtimeActivityLogPage
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val blePermissions = mutableListOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> /* 权限结果由 Composable 中检查 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missing = blePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }

        setContent {
            LifeLogTheme {
                LifeLogNavHost()
            }
        }
    }
}

@Composable
fun LifeLogNavHost() {
    var currentPage by remember { mutableIntStateOf(0) }
    var showLogViewer by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { 3 })
    val context = LocalContext.current

    // App 启动时触发一次后台同步（只一次，切页不会重新触发）
    LaunchedEffect(Unit) {
        val intent = Intent(context, BleSyncService::class.java).apply {
            action = BleSyncService.ACTION_START_SYNC
        }
        try { context.startForegroundService(intent) } catch (_: Exception) {}
    }

    // 返回键/侧滑返回：不是首页就切到前一页，是首页交给系统（最小化 App）
    BackHandler(enabled = currentPage > 0) {
        currentPage = currentPage - 1
    }

    // 监听 SettingsViewModel 的实时模式状态（切换后自动刷新 UI）
    val settingsViewModel: com.lifelog.camera.ui.settings.SettingsViewModel = hiltViewModel()
    val isRealtime by settingsViewModel.isRealtimeMode.collectAsState()

    val navItems = if (isRealtime) rpgNavItems else diaryNavItems

    // 同步 Pager ↔ 胶囊导航
    var isCapsuleClick by remember { mutableStateOf(false) }
    LaunchedEffect(currentPage) {
        isCapsuleClick = true
        pagerState.animateScrollToPage(currentPage)
        isCapsuleClick = false
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { page ->
                if (!isCapsuleClick) currentPage = page
            }
    }

    if (showLogViewer) {
        LogViewerScreen(onBack = { showLogViewer = false })
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 日期条
            BookmarkDateBar(
                titleOverride = when {
                    currentPage == 2 -> "设置"
                    !isRealtime -> null  // 日志模式：显示日期
                    else -> when (currentPage) {
                        0 -> "角色状态"
                        1 -> "活动日志"
                        else -> null
                    }
                }
            )

            // 页面内容
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) { page ->
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isRealtime) {
                        when (page) {
                            0 -> RealtimeCharacterPage()
                            1 -> RealtimeActivityLogPage()
                            2 -> SettingsScreen(onNavigateToLog = { showLogViewer = true })
                        }
                    } else {
                        when (page) {
                            0 -> TimelineScreen(onNavigateToLog = { showLogViewer = true })
                            1 -> ReportScreen()
                            2 -> SettingsScreen(onNavigateToLog = { showLogViewer = true })
                        }
                    }
                }
            }
        }

        // 悬浮胶囊导航
        FloatingCapsuleNav(
            selectedIndex = currentPage,
            onItemClick = { currentPage = it },
            items = navItems,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
