@file:OptIn(ExperimentalMaterial3Api::class)

package com.goanalyzer.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import com.goanalyzer.data.FoxwqClient
import com.goanalyzer.data.FoxwqDownloader
import com.goanalyzer.data.FoxwqGame
import com.goanalyzer.data.FoxwqFollowRepository
import com.goanalyzer.data.FoxwqUser
import com.goanalyzer.data.SgfParser
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ============ 对话框状态机 ============
private sealed class FoxwqDialogState {
    data object Search : FoxwqDialogState()
    data class Searching(val username: String) : FoxwqDialogState()
    data class UserReady(val user: FoxwqUser, val games: List<FoxwqGame>, val loadingMore: Boolean) : FoxwqDialogState()
    data class LoadingGamesFailed(val user: FoxwqUser, val error: String) : FoxwqDialogState()
    data class Downloading(val game: FoxwqGame) : FoxwqDialogState()
    data class Error(val message: String) : FoxwqDialogState()
    data object Followed : FoxwqDialogState()
}

// ============ 下载反馈状态 ============
private data class DownloadFeedback(
    val isLoading: Boolean = false,
    val message: String = "",
    val isError: Boolean = false
)

// ============ 主对话框 ============
@Composable
fun FoxwqImportDialog(
    foxwqClient: FoxwqClient,
    followRepository: FoxwqFollowRepository,
    onDismiss: () -> Unit,
    onGameImported: (sgfContent: String, gameName: String) -> Unit,
) {
    var state by remember { mutableStateOf<FoxwqDialogState>(FoxwqDialogState.Search) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val downloader = remember { FoxwqDownloader(context) }
    var downloadFeedback by remember { mutableStateOf<DownloadFeedback>(DownloadFeedback()) }

    // 初始搜索用户名
    var searchText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // 加载更多分页
    var lastLoadedCode by remember { mutableStateOf("0") }
    var allGames by remember { mutableStateOf<List<FoxwqGame>>(emptyList()) }
    var currentUser by remember { mutableStateOf<FoxwqUser?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- Snackbar 反馈 ----
                val snackbarHostState = remember { SnackbarHostState() }

                // ---- 顶部标题栏 ----
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🐾", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("从野狐导入", fontWeight = FontWeight.Bold)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "关闭")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    )
                )

                // ---- Tab切换：搜索 / 关注的棋手 ----
                var selectedTab by remember { mutableIntStateOf(0) }
                val followedUsers by followRepository.followedUsers.collectAsState(initial = emptyList())

                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            state = FoxwqDialogState.Search
                        },
                        text = { Text("搜索棋手") },
                        icon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            state = FoxwqDialogState.Followed
                        },
                        text = { Text("关注的棋手") },
                        icon = { Icon(Icons.Default.Star, null, modifier = Modifier.size(18.dp)) }
                    )
                }

                // ---- 内容区 ----
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val s = state) {
                        is FoxwqDialogState.Search -> {
                            SearchPane(
                                searchText = searchText,
                                onSearchTextChange = { searchText = it },
                                onSearch = {
                                    focusManager.clearFocus()
                                    val name = searchText.trim()
                                    if (name.isNotEmpty()) {
                                        state = FoxwqDialogState.Searching(name)
                                        scope.launch {
                                            val result = foxwqClient.queryUser(name)
                                            result.fold(
                                                onSuccess = { user ->
                                                    currentUser = user
                                                    lastLoadedCode = "0"
                                                    allGames = emptyList()
                                                    val listResult = foxwqClient.getGameList(user.uid)
                                                    listResult.fold(
                                                        onSuccess = { games ->
                                                            allGames = games
                                                            lastLoadedCode = games.lastOrNull()?.chessId ?: "0"
                                                            state = FoxwqDialogState.UserReady(user, games, false)
                                                        },
                                                        onFailure = { e ->
                                                            state = FoxwqDialogState.LoadingGamesFailed(user, e.message ?: "加载失败")
                                                        }
                                                    )
                                                },
                                                onFailure = { e ->
                                                    state = FoxwqDialogState.Error(e.message ?: "用户不存在")
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }

                        is FoxwqDialogState.Searching -> {
                            LoadingPane("正在搜索「${s.username}」...")
                        }

                        is FoxwqDialogState.UserReady -> {
                            GameListPane(
                                user = s.user,
                                games = s.games,
                                loadingMore = s.loadingMore,
                                onGameClick = { game ->
                                    state = FoxwqDialogState.Downloading(game)
                                    scope.launch {
                                        val result = foxwqClient.getSgf(game.chessId)
                                        result.fold(
                                            onSuccess = { sgf ->
                                                // 注入野狐段位（BR/WR）再解析
                                                val blackRank = SgfParser.danToRankString(game.blackDan)
                                                val whiteRank = SgfParser.danToRankString(game.whiteDan)
                                                val sgfWithRanks = SgfParser.injectRanks(sgf, blackRank, whiteRank)
                                                val name = "${game.blackNick} vs ${game.whiteNick} (${game.winnerString})"
                                                onGameImported(sgfWithRanks, name)
                                                onDismiss()
                                            },
                                            onFailure = { e ->
                                                state = FoxwqDialogState.Error("下载失败: ${e.message}")
                                            }
                                        )
                                    }
                                },
                                onDownloadClick = { game ->
                                    downloadFeedback = DownloadFeedback(isLoading = true, message = "正在保存...")
                                    scope.launch {
                                        val result = foxwqClient.getSgf(game.chessId)
                                        result.fold(
                                            onSuccess = { sgf ->
                                                val saveResult = downloader.downloadGame(sgf, game)
                                                saveResult.fold(
                                                    onSuccess = { path ->
                                                        downloadFeedback = DownloadFeedback(
                                                            isLoading = false,
                                                            message = "已保存到 $path",
                                                            isError = false
                                                        )
                                                        snackbarHostState.showSnackbar("已保存到 $path")
                                                    },
                                                    onFailure = { e ->
                                                        downloadFeedback = DownloadFeedback(
                                                            isLoading = false,
                                                            message = "保存失败: ${e.message}",
                                                            isError = true
                                                        )
                                                        snackbarHostState.showSnackbar("保存失败: ${e.message}")
                                                    }
                                                )
                                            },
                                            onFailure = { e ->
                                                downloadFeedback = DownloadFeedback(
                                                    isLoading = false,
                                                    message = "获取棋谱失败: ${e.message}",
                                                    isError = true
                                                )
                                                snackbarHostState.showSnackbar("获取棋谱失败: ${e.message}")
                                            }
                                        )
                                    }
                                },
                                onLoadMore = {
                                    val uid = currentUser?.uid ?: return@GameListPane
                                    state = FoxwqDialogState.UserReady(s.user, s.games, true)
                                    scope.launch {
                                        val result = foxwqClient.getGameList(uid, lastLoadedCode)
                                        result.fold(
                                            onSuccess = { newGames ->
                                                allGames = allGames + newGames
                                                lastLoadedCode = newGames.lastOrNull()?.chessId ?: lastLoadedCode
                                                state = FoxwqDialogState.UserReady(s.user, allGames, false)
                                            },
                                            onFailure = {
                                                state = FoxwqDialogState.UserReady(s.user, s.games, false)
                                            }
                                        )
                                    }
                                },
                                followRepository = followRepository,
                                onFollowToggle = { user ->
                                    scope.launch {
                                        if (followRepository.isFollowing(user.uid)) {
                                            followRepository.unfollowUser(user.uid)
                                        } else {
                                            followRepository.followUser(user)
                                        }
                                    }
                                }
                            )
                        }

                        is FoxwqDialogState.LoadingGamesFailed -> {
                            GameListPane(
                                user = s.user,
                                games = emptyList(),
                                loadingMore = false,
                                listError = s.error,
                                onGameClick = {},
                                onDownloadClick = {},
                                onLoadMore = {},
                                followRepository = followRepository,
                                onFollowToggle = { user ->
                                    scope.launch {
                                        if (followRepository.isFollowing(user.uid)) {
                                            followRepository.unfollowUser(user.uid)
                                        } else {
                                            followRepository.followUser(user)
                                        }
                                    }
                                }
                            )
                        }

                        is FoxwqDialogState.Downloading -> {
                            LoadingPane("正在下载棋谱...\n${s.game.displayTitle}")
                        }

                        is FoxwqDialogState.Error -> {
                            ErrorPane(
                                message = s.message,
                                onRetry = { state = FoxwqDialogState.Search }
                            )
                        }

                        is FoxwqDialogState.Followed -> {
                            FollowedPane(
                                foxwqClient = foxwqClient,
                                followRepository = followRepository,
                                onUserClick = { user ->
                                    currentUser = user
                                    lastLoadedCode = "0"
                                    allGames = emptyList()
                                    state = FoxwqDialogState.Searching(user.username)
                                    scope.launch {
                                        val result = foxwqClient.getGameList(user.uid)
                                        result.fold(
                                            onSuccess = { games ->
                                                allGames = games
                                                lastLoadedCode = games.lastOrNull()?.chessId ?: "0"
                                                state = FoxwqDialogState.UserReady(user, games, false)
                                            },
                                            onFailure = { e ->
                                                state = FoxwqDialogState.LoadingGamesFailed(user, e.message ?: "加载失败")
                                            }
                                        )
                                    }
                                },
                                onBack = { selectedTab = 0 }
                            )
                        }
                    }
                }

                // ---- 底部提示 ----
                SnackbarHost(hostState = snackbarHostState)

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    tonalElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "野狐公开接口，无需登录 · 数据来源：h5.foxwq.com",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

// ============ 搜索面板 ============
@Composable
private fun SearchPane(
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // 搜索图标区
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Search,
                null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "搜索野狐棋手",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "输入棋手昵称，查看最近对局并一键分析",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = searchText,
            onValueChange = onSearchTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("输入棋手昵称，如：柯洁、空中357") },
            leadingIcon = {
                Icon(Icons.Default.Person, null, modifier = Modifier.size(20.dp))
            },
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = { onSearchTextChange("") }) {
                        Icon(Icons.Default.Clear, "清除", modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSearch,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = searchText.isNotBlank(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("搜索", fontSize = 15.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 常用棋手快捷入口
        Text(
            "热门棋手",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("柯洁", "申真谞", "朴廷桓", "芈昱廷").forEach { name ->
                AssistChip(
                    onClick = {
                        onSearchTextChange(name)
                        onSearch()
                    },
                    label = { Text(name, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ============ 游戏列表面板 ============
@Composable
private fun GameListPane(
    user: FoxwqUser,
    games: List<FoxwqGame>,
    loadingMore: Boolean,
    listError: String? = null,
    onGameClick: (FoxwqGame) -> Unit,
    onLoadMore: () -> Unit,
    onDownloadClick: (FoxwqGame) -> Unit,
    followRepository: FoxwqFollowRepository? = null,
    onFollowToggle: ((FoxwqUser) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    var isFollowing by remember(user.uid) { mutableStateOf(followRepository?.isFollowing(user.uid) ?: false) }

    // 滚动到底部时加载更多
    LaunchedEffect(listState, games.size, loadingMore) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= games.size - 3
        }.collect { shouldLoad ->
            if (shouldLoad && games.isNotEmpty() && !loadingMore) {
                onLoadMore()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 用户信息头
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        user.username.take(1),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        user.username,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${user.danString} · ${user.totalWin}胜 ${user.totalLoss}负 · 共${user.totalGames}局",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onFollowToggle != null) {
                    IconButton(onClick = {
                        isFollowing = !isFollowing
                        onFollowToggle(user)
                    }) {
                        Icon(
                            if (isFollowing) Icons.Default.Star else Icons.Default.StarBorder,
                            if (isFollowing) "取消关注" else "关注",
                            tint = if (isFollowing) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    TextButton(onClick = { /* 重置搜索 */ }) {
                        Text("重新搜索", fontSize = 12.sp)
                    }
                }
            }
        }

        // 棋谱列表
        if (listError != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Warning,
                        null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(listError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        } else if (games.isEmpty() && !loadingMore) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(games, key = { it.chessId }) { game ->
                    GameListItem(
                        game = game,
                        onClick = { onGameClick(game) },
                        onDownloadClick = { onDownloadClick(game) }
                    )
                }

                if (loadingMore) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

// ============ 单条对局项 ============
@Composable
private fun GameListItem(
    game: FoxwqGame,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 结果标识
            val icon = when (game.winner) {
                1 -> "⚫"
                2 -> "⚪"
                else -> "🟡"
            }
            Text(icon, fontSize = 16.sp)

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    game.displayTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    game.displaySubtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 下载按钮
            IconButton(
                onClick = onDownloadClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Download,
                    "下载",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

// ============ 加载中面板 ============
@Composable
private fun LoadingPane(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text.replace("\n", " "),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============ 错误面板 ============
@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.ErrorOutline,
                null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                message,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry, shape = RoundedCornerShape(12.dp)) {
                Text("重新搜索")
            }
        }
    }
}

// ============ 关注的棋手面板 ============
@Composable
private fun FollowedPane(
    foxwqClient: FoxwqClient,
    followRepository: FoxwqFollowRepository,
    onUserClick: (FoxwqUser) -> Unit,
    onBack: () -> Unit,
) {
    val followedUsers by followRepository.followedUsers.collectAsState(initial = emptyList())

    if (followedUsers.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.StarBorder,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "还没有关注的棋手",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "搜索棋手后可关注",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onBack) {
                    Text("去搜索")
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(followedUsers, key = { it.uid }) { user ->
                FollowUserCard(
                    user = user,
                    onClick = { onUserClick(user) },
                    onUnfollow = {
                        kotlinx.coroutines.MainScope().launch {
                            followRepository.unfollowUser(user.uid)
                        }
                    }
                )
            }
        }
    }
}

// ============ 关注的棋手卡片 ============
@Composable
private fun FollowUserCard(
    user: FoxwqUser,
    onClick: () -> Unit,
    onUnfollow: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    user.username.take(1),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    user.username,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "${user.danString} · ${user.totalGames}局",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onUnfollow) {
                Icon(
                    Icons.Default.Star,
                    "取消关注",
                    modifier = Modifier.size(20.dp),
                    tint = Color(0xFFFFB300)
                )
            }
        }
    }
}
