package dev.infinityf4p.tiebapure

import android.content.Context
import android.content.ContentValues
import android.app.Activity
import android.content.Intent
import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.core.view.WindowCompat
import dev.infinityf4p.tiebapure.core.data.AppAppearance
import dev.infinityf4p.tiebapure.core.data.AppSettings
import dev.infinityf4p.tiebapure.core.designsystem.ReaderState
import dev.infinityf4p.tiebapure.core.designsystem.LocalReaderFontRevision
import dev.infinityf4p.tiebapure.core.designsystem.ReaderStatePane
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
import dev.infinityf4p.tiebapure.core.designsystem.ThreadMediaPreviewAction
import dev.infinityf4p.tiebapure.core.designsystem.resolveThreadMediaPreviewAction
import dev.infinityf4p.tiebapure.core.media.MediaUrlPolicy
import dev.infinityf4p.tiebapure.core.media.OfflineMediaPolicy
import dev.infinityf4p.tiebapure.core.media.ImageSaveAction
import dev.infinityf4p.tiebapure.core.media.SecureImageDownloadClient
import dev.infinityf4p.tiebapure.core.media.ThreadMediaPreviewDialog
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionTarget
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
import dev.infinityf4p.tiebapure.core.model.mutationOutcomeUnknownMessageOrNull
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.feature.account.AccountDestination
import dev.infinityf4p.tiebapure.feature.account.BrowsingHistoryRoute
import dev.infinityf4p.tiebapure.feature.account.BrowsingHistoryViewModel
import dev.infinityf4p.tiebapure.feature.account.EditProfileRoute
import dev.infinityf4p.tiebapure.feature.account.EditProfileViewModel
import dev.infinityf4p.tiebapure.feature.account.LoginRoute
import dev.infinityf4p.tiebapure.feature.account.LoginViewModel
import dev.infinityf4p.tiebapure.feature.account.MeRoute
import dev.infinityf4p.tiebapure.feature.account.MeViewModel
import dev.infinityf4p.tiebapure.feature.account.MessagesRoute
import dev.infinityf4p.tiebapure.feature.account.MessagesViewModel
import dev.infinityf4p.tiebapure.feature.account.ThreadFavoritesRoute
import dev.infinityf4p.tiebapure.feature.account.ThreadFavoritesViewModel
import dev.infinityf4p.tiebapure.feature.account.UserProfileRoute
import dev.infinityf4p.tiebapure.feature.account.UserProfileViewModel
import dev.infinityf4p.tiebapure.feature.account.UserRelationshipsRoute
import dev.infinityf4p.tiebapure.feature.account.UserRelationshipsViewModel
import dev.infinityf4p.tiebapure.feature.composer.ComposerViewModel
import dev.infinityf4p.tiebapure.feature.composer.ComposerSubmissionCapability
import dev.infinityf4p.tiebapure.feature.composer.ContentComposerRoute
import dev.infinityf4p.tiebapure.feature.forum.ForumHubRoute
import dev.infinityf4p.tiebapure.feature.forum.ForumHubViewModel
import dev.infinityf4p.tiebapure.feature.forum.ForumThreadsCallbacks
import dev.infinityf4p.tiebapure.feature.forum.ForumThreadsCapabilities
import dev.infinityf4p.tiebapure.feature.forum.ForumThreadsRoute
import dev.infinityf4p.tiebapure.feature.forum.ForumThreadsViewModel
import dev.infinityf4p.tiebapure.feature.home.HomeCallbacks
import dev.infinityf4p.tiebapure.feature.home.HomeRoute
import dev.infinityf4p.tiebapure.feature.home.HomeViewModel
import dev.infinityf4p.tiebapure.feature.search.SearchCallbacks
import dev.infinityf4p.tiebapure.feature.search.SearchRoute
import dev.infinityf4p.tiebapure.feature.search.SearchScope
import dev.infinityf4p.tiebapure.feature.search.SearchViewModel
import dev.infinityf4p.tiebapure.feature.settings.AboutSettingsScreen
import dev.infinityf4p.tiebapure.feature.settings.BlocklistSettingsRoute
import dev.infinityf4p.tiebapure.feature.settings.ReadingSettingsRoute
import dev.infinityf4p.tiebapure.feature.settings.SettingsAboutInfo
import dev.infinityf4p.tiebapure.feature.settings.SettingsDestination
import dev.infinityf4p.tiebapure.feature.settings.SettingsHostState
import dev.infinityf4p.tiebapure.feature.settings.SettingsRoute
import dev.infinityf4p.tiebapure.feature.settings.SettingsViewModel
import dev.infinityf4p.tiebapure.feature.thread.ThreadCapabilities
import dev.infinityf4p.tiebapure.feature.thread.ThreadInitialDestination
import dev.infinityf4p.tiebapure.feature.thread.ThreadReplyTarget
import dev.infinityf4p.tiebapure.feature.thread.ThreadRoute
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class TiebaPureWindowWidth { Compact, Medium, Expanded }

internal fun windowWidthFor(width: Dp): TiebaPureWindowWidth = when {
    width < 600.dp -> TiebaPureWindowWidth.Compact
    width < 840.dp -> TiebaPureWindowWidth.Medium
    else -> TiebaPureWindowWidth.Expanded
}

internal data class ExpandedPaneWidths(
    val list: Dp,
    val detail: Dp,
)

internal val ExpandedNavigationRailWidth = 72.dp
internal val ExpandedPaneDividerWidth = 1.dp

internal fun expandedPaneWidthsFor(width: Dp): ExpandedPaneWidths {
    require(width >= 840.dp) { "Expanded panes require at least 840dp." }
    val available = width - ExpandedNavigationRailWidth - ExpandedPaneDividerWidth * 2
    val preferredList = (available * 0.4f).coerceIn(320.dp, 560.dp)
    val list = preferredList.coerceAtMost(available - 440.dp)
    return ExpandedPaneWidths(list = list, detail = available - list)
}

private enum class RootDestination(val route: String, val label: String) {
    Home("home", "首页"),
    Forums("forums", "进吧"),
    Me("me", "我的"),
}

private object Routes {
    const val DetailEmpty = "detail-empty"
    const val Search = "search"
    const val SearchPattern = "$Search?query={query}"
    const val ForumSearch = "search/forum/{forumName}"
    const val Forum = "forum/{forumName}"
    const val Thread = "thread/{threadId}?postId={postId}&initialDestination={initialDestination}"
    const val Login = "login"
    const val Settings = "settings"
    const val Reading = "settings/reading"
    const val Blocklist = "settings/blocklist"
    const val About = "about"
    const val History = "history"
    const val SavedThreads = "saved-threads"
    const val SavedThread = "saved-thread/{threadId}"
    const val Favorites = "favorites"
    const val Messages = "messages"
    const val User = "user/{userId}/{userName}"
    const val Relationships = "relationships/{userId}/{userName}/{kind}"
    const val EditProfile = "edit-profile"
    const val Composer =
        "compose/{kind}/{forumId}/{forumName}/{threadId}/{parentPostId}/{parentFloor}/{subpostId}/{replyUserId}/{replyUserName}"
}

@Composable
internal fun TiebaPureRoot(
    externalNavigationEvent: ExternalNavigationEvent? = null,
    onExternalNavigationConsumed: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val container = (context.applicationContext as TiebaPureApplication).container
    val settings by container.currentSettings.collectAsStateWithLifecycle()
    val readerFontRevision by container.readerFonts.revision.collectAsStateWithLifecycle()
    val darkTheme = when (settings.appearance) {
        AppAppearance.System -> androidx.compose.foundation.isSystemInDarkTheme()
        AppAppearance.Light -> false
        AppAppearance.Dark -> true
    }
    val view = LocalView.current
    val activity = context.findActivity()
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    TiebaPureTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(LocalReaderFontRevision provides readerFontRevision) {
            TiebaPureApp(
                container = container,
                settings = settings,
                externalNavigationEvent = externalNavigationEvent,
                onExternalNavigationConsumed = onExternalNavigationConsumed,
            )
        }
    }
}

@Composable
internal fun TiebaPureApp(
    container: AppContainer,
    settings: AppSettings,
    externalNavigationEvent: ExternalNavigationEvent? = null,
    onExternalNavigationConsumed: (Long) -> Unit = {},
) {
    val sessionExpirationNotice by container.sessionExpiration.notice.collectAsStateWithLifecycle()
    val rootNavController = rememberNavController()
    val listPaneNavController = rememberNavController()
    var selectedDestination by rememberSaveable { mutableStateOf(RootDestination.Home) }
    var homeRefreshRequest by rememberSaveable { mutableLongStateOf(0L) }
    val rootBackStackEntry by rootNavController.currentBackStackEntryAsState()
    val listPaneBackStackEntry by listPaneNavController.currentBackStackEntryAsState()
    val composerHasFocus = isComposerDestinationRoute(rootBackStackEntry?.destination?.route)
    val compactShowsRootNavigation = shouldShowCompactRootNavigation(rootBackStackEntry?.destination?.route)
    LaunchedEffect(rootBackStackEntry?.destination?.route) {
        RootDestination.entries.firstOrNull { it.route == rootBackStackEntry?.destination?.route }
            ?.let { selectedDestination = it }
    }
    LaunchedEffect(externalNavigationEvent?.id) {
        val event = externalNavigationEvent ?: return@LaunchedEffect
        rootNavController.navigate(buildExternalNavigationRoute(event.destination, Uri::encode)) {
            launchSingleTop = true
        }
        onExternalNavigationConsumed(event.id)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val horizontalSafeDrawing = with(density) {
            val insets = WindowInsets.safeDrawing
            (insets.getLeft(this, layoutDirection) + insets.getRight(this, layoutDirection)).toDp()
        }
        val availableWidth = (maxWidth - horizontalSafeDrawing).coerceAtLeast(0.dp)
        val widthClass = windowWidthFor(availableWidth)
        LaunchedEffect(widthClass, selectedDestination) {
            if (widthClass == TiebaPureWindowWidth.Expanded) {
                listPaneNavController.navigateToRoot(selectedDestination)
            }
        }
        when (widthClass) {
            TiebaPureWindowWidth.Compact -> {
                val navigateToRoot: (RootDestination) -> Unit = { destination ->
                    if (shouldRequestHomeRefresh(
                            selectedRootRoute = selectedDestination.route,
                            tappedRootRoute = destination.route,
                            primaryRoute = rootBackStackEntry?.destination?.route,
                        )
                    ) {
                        homeRefreshRequest += 1
                    } else {
                        selectedDestination = destination
                        rootNavController.navigateToRoot(destination)
                    }
                }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (compactShowsRootNavigation) RootNavigationBar(selectedDestination, navigateToRoot)
                    },
                ) { padding ->
                    AppNavigationHost(
                        container = container,
                        settings = settings,
                        navController = rootNavController,
                        onSelectRoot = navigateToRoot,
                        homeRefreshRequest = homeRefreshRequest,
                        modifier = Modifier.padding(padding),
                    )
                }
            }

            TiebaPureWindowWidth.Medium -> {
                val navigateToRoot: (RootDestination) -> Unit = { destination ->
                    if (shouldRequestHomeRefresh(
                            selectedRootRoute = selectedDestination.route,
                            tappedRootRoute = destination.route,
                            primaryRoute = rootBackStackEntry?.destination?.route,
                        )
                    ) {
                        homeRefreshRequest += 1
                    } else {
                        selectedDestination = destination
                        rootNavController.navigateToRoot(destination)
                    }
                }
                if (composerHasFocus) {
                    AppNavigationHost(
                        container = container,
                        settings = settings,
                        navController = rootNavController,
                        onSelectRoot = navigateToRoot,
                        homeRefreshRequest = homeRefreshRequest,
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                } else {
                    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        RootNavigationRail(selectedDestination, navigateToRoot)
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AppNavigationHost(
                            container = container,
                            settings = settings,
                            navController = rootNavController,
                            onSelectRoot = navigateToRoot,
                            homeRefreshRequest = homeRefreshRequest,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            TiebaPureWindowWidth.Expanded -> {
                val navigateToRoot: (RootDestination) -> Unit = { destination ->
                    if (shouldRequestHomeRefresh(
                            selectedRootRoute = selectedDestination.route,
                            tappedRootRoute = destination.route,
                            primaryRoute = listPaneBackStackEntry?.destination?.route,
                            detailRoute = rootBackStackEntry?.destination?.route,
                        )
                    ) {
                        homeRefreshRequest += 1
                    } else {
                        selectedDestination = destination
                        listPaneNavController.navigateToRoot(destination)
                        rootNavController.navigateToRoot(destination)
                    }
                }
                if (composerHasFocus) {
                    AppNavigationHost(
                        container = container,
                        settings = settings,
                        navController = rootNavController,
                        onSelectRoot = navigateToRoot,
                        homeRefreshRequest = homeRefreshRequest,
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                } else {
                    val panes = expandedPaneWidthsFor(availableWidth)
                    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        RootNavigationRail(
                            selected = selectedDestination,
                            onSelected = navigateToRoot,
                            modifier = Modifier.width(ExpandedNavigationRailWidth),
                        )
                        VerticalDivider(
                            modifier = Modifier.width(ExpandedPaneDividerWidth),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        AppNavigationHost(
                            container = container,
                            settings = settings,
                            navController = listPaneNavController,
                            destinationNavController = rootNavController,
                            onSelectRoot = navigateToRoot,
                            homeRefreshRequest = homeRefreshRequest,
                            modifier = Modifier.width(panes.list),
                        )
                        VerticalDivider(
                            modifier = Modifier.width(ExpandedPaneDividerWidth),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        AppNavigationHost(
                            container = container,
                            settings = settings,
                            navController = rootNavController,
                            onSelectRoot = navigateToRoot,
                            homeRefreshRequest = homeRefreshRequest,
                            modifier = Modifier.width(panes.detail),
                            rootDestinationsAsEmpty = true,
                        )
                    }
                }
            }
        }
    }
    sessionExpirationNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = container.sessionExpiration::dismissNotice,
            title = { Text("登录已失效") },
            text = { Text(notice.message) },
            confirmButton = {
                TextButton(onClick = container.sessionExpiration::dismissNotice) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun AppNavigationHost(
    container: AppContainer,
    settings: AppSettings,
    navController: NavHostController,
    onSelectRoot: (RootDestination) -> Unit,
    homeRefreshRequest: Long,
    modifier: Modifier,
    destinationNavController: NavHostController = navController,
    startDestination: String = RootDestination.Home.route,
    rootDestinationsAsEmpty: Boolean = false,
) {
    val context = LocalContext.current
    val account by container.account.collectAsStateWithLifecycle()
    val savedThreadEntries by container.savedThreads.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val accountSessionKey = sessionViewModelKey(account)
    val scope = rememberCoroutineScope()
    val versionName = remember(context) { appVersionName(context) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val settingsViewModel: SettingsViewModel = viewModel(
        key = "settings",
        factory = SettingsViewModel.factory(container.settingsRepository, container.settingsAccountActions),
    )
    val imageDownloader = remember(context.applicationContext) {
        SecureImageDownloadClient(context.applicationContext)
    }
    var mediaPreviewAction by remember { mutableStateOf<ThreadMediaPreviewAction?>(null) }
    val openThreadMedia: (dev.infinityf4p.tiebapure.core.model.ThreadSummary, Int) -> Unit = { thread, index ->
        mediaPreviewAction = resolveThreadMediaPreviewAction(thread.blocks, index)
    }
    val imageSaveMutex = remember { Mutex() }
    val pendingImageSavePermission = remember {
        AtomicReference<CancellableContinuation<Unit>?>(null)
    }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingImageSavePermission.getAndSet(null)
        if (pending?.isActive == true) {
            pending.resumeWith(
                if (granted) {
                    Result.success(Unit)
                } else {
                    Result.failure(SecurityException("需要存储权限才能在 Android 9 及以下版本保存图片。"))
                },
            )
        }
    }
    val saveImageAction: ImageSaveAction = { image, onProgress ->
        imageSaveMutex.withLock {
            val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                suspendCancellableCoroutine { continuation ->
                    check(pendingImageSavePermission.compareAndSet(null, continuation)) {
                        "已有图片正在等待存储权限。"
                    }
                    continuation.invokeOnCancellation {
                        pendingImageSavePermission.compareAndSet(continuation, null)
                    }
                    runCatching {
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }.onFailure { error ->
                        pendingImageSavePermission.compareAndSet(continuation, null)
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    }
                }
            }
            saveImageToMediaStore(context, imageDownloader, image, onProgress)
        }
    }
    val requestImageDownload: (ImageContent) -> Unit = { image ->
        scope.launch {
            runCatching { saveImageAction(image) {} }
                .onSuccess { message = "图片已保存到相册。" }
                .onFailure { error -> message = readableMessage(error) }
        }
    }

    val openComposer: (ContentSubmissionTarget) -> Unit = { target ->
        val enabled = when (target.kind) {
            ContentSubmissionKind.NewThread -> settings.postingEnabled
            else -> settings.replyingEnabled
        }
        when {
            account == null -> message = "请先登录贴吧账号。"
            !enabled -> message = if (target.kind == ContentSubmissionKind.NewThread) {
                "请先在设置中开启允许发帖。"
            } else {
                "请先在设置中开启允许回帖。"
            }
            else -> destinationNavController.navigate(composerRoute(target))
        }
    }

    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.DetailEmpty) {
                ReaderStatePane(ReaderState.Empty("暂无选中内容"), Modifier.fillMaxSize())
            }

            composable(RootDestination.Home.route) {
                if (rootDestinationsAsEmpty) {
                    EmptyDetailPane()
                } else {
                    val home: HomeViewModel = viewModel(
                        key = "home-$accountSessionKey",
                        factory = factory { HomeViewModel(container.featureRepositories.home) },
                    )
                    HomeRoute(
                        viewModel = home,
                        programmaticRefreshRequest = homeRefreshRequest,
                        mediaLoadingPolicy = settings.reading.mediaLoading,
                        callbacks = HomeCallbacks(
                            canLike = settings.likingEnabled,
                            onOpenSearch = { destinationNavController.navigate(Routes.Search) },
                        onOpenThread = {
                            container.featureRepositories.rememberThreadSummary(it)
                            destinationNavController.navigate(threadRoute(it.id))
                        },
                        onOpenComments = {
                            container.featureRepositories.rememberThreadSummary(it)
                            destinationNavController.navigate(
                                threadRoute(it.id, initialDestination = ThreadInitialDestination.Replies),
                            )
                        },
                        onOpenForum = { destinationNavController.navigate(forumRoute(it)) },
                        onOpenUser = { destinationNavController.navigate(userRoute(it)) },
                        onBlockForum = { forum ->
                            scope.launch {
                                runCatching { container.addBlockedForum(forum) }
                                    .onSuccess { home.removeForum(forum) }
                                    .onFailure { message = readableMessage(it) }
                            }
                        },
                        onToggleLike = { thread ->
                            val current = account
                            val firstPostId = thread.firstPostId
                            when {
                                !settings.likingEnabled -> message = "请先在设置中开启允许点赞。"
                                current == null -> message = "登录后才能点赞。"
                                firstPostId == null -> message = "暂时无法获取该帖的点赞信息。"
                                else -> home.beginLikeMutation(thread.id)?.let { currentThread -> scope.launch {
                                    val targetLiked = !currentThread.isLiked
                                    runCatching {
                                        container.mutationRepository.setPostLiked(
                                            current,
                                            thread.id,
                                            firstPostId,
                                            TiebaLikeObjectType.Thread,
                                            targetLiked,
                                        )
                                    }.onSuccess { home.completeLikeMutation(thread.id, targetLiked) }
                                        .onFailure {
                                            val unknown = it.mutationOutcomeUnknownMessageOrNull()
                                            if (unknown != null) home.markLikeOutcomeUnknown(thread.id, unknown)
                                            else home.failLikeMutation(thread.id)
                                            message = unknown ?: readableMessage(it)
                                        }
                                } }
                            }
                        },
                        onOpenMedia = openThreadMedia,
                        ),
                    )
                }
            }

            composable(RootDestination.Forums.route) {
                if (rootDestinationsAsEmpty) {
                    EmptyDetailPane()
                } else {
                    val hub: ForumHubViewModel = viewModel(
                        key = "forum-hub-$accountSessionKey",
                        factory = factory { ForumHubViewModel(container.featureRepositories.forumHub) },
                    )
                    ForumHubRoute(
                        viewModel = hub,
                        onOpenForum = { forum ->
                            destinationNavController.navigate(forumRoute(forum))
                        },
                    )
                }
            }

            composable(RootDestination.Me.route) {
                if (rootDestinationsAsEmpty) {
                    EmptyDetailPane()
                } else {
                    val me: MeViewModel = viewModel(factory = factory { MeViewModel(container.accountFeatures.me) })
                    MeRoute(
                        viewModel = me,
                        onLogin = { destinationNavController.navigate(Routes.Login) },
                        onOpen = { destination ->
                            when (destination) {
                            AccountDestination.OwnProfile -> account?.let {
                                destinationNavController.navigate(userRoute(it.toUserSummary()))
                            } ?: run { message = "请先登录贴吧账号。" }
                            AccountDestination.Messages -> destinationNavController.navigate(Routes.Messages)
                            AccountDestination.FollowedUsers -> account?.let {
                                destinationNavController.navigate(
                                    relationshipRoute(it.toUserSummary(), UserRelationshipKind.Following),
                                )
                            } ?: run { message = "请先登录贴吧账号。" }
                            AccountDestination.FollowedForums -> onSelectRoot(RootDestination.Forums)
                            AccountDestination.ThreadFavorites -> destinationNavController.navigate(Routes.Favorites)
                            AccountDestination.BrowsingHistory -> destinationNavController.navigate(Routes.History)
                            AccountDestination.Settings -> destinationNavController.navigate(Routes.Settings)
                            AccountDestination.About -> destinationNavController.navigate(Routes.About)
                            }
                        },
                    )
                }
            }

            composable(
                route = Routes.SearchPattern,
                arguments = listOf(
                    navArgument("query") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val initialKeyword = Uri.decode(entry.arguments?.getString("query").orEmpty())
                SearchPage(
                    navController,
                    container.featureRepositories.search,
                    SearchScope.Global,
                    initialKeyword,
                    container.featureRepositories::rememberThreadSummary,
                    settings.reading.mediaLoading,
                    openThreadMedia,
                )
            }
            composable(Routes.ForumSearch) { entry ->
                val name = Uri.decode(entry.arguments?.getString("forumName").orEmpty()).removeSuffix("吧")
                SearchPage(
                    navController,
                    container.featureRepositories.search,
                    SearchScope.ForumOnly(Forum(0, name, "${name}吧")),
                    "",
                    container.featureRepositories::rememberThreadSummary,
                    settings.reading.mediaLoading,
                    openThreadMedia,
                )
            }

            composable(Routes.Forum) { entry ->
                val name = Uri.decode(entry.arguments?.getString("forumName").orEmpty()).removeSuffix("吧")
                val forum = Forum(0, name, "${name}吧")
                val forumViewModel: ForumThreadsViewModel = viewModel(
                    key = "forum-$name-$accountSessionKey",
                    factory = factory {
                        ForumThreadsViewModel(
                            forum,
                            container.featureRepositories.forumThreads,
                            container.featureRepositories.forumInteraction,
                            container.featureRepositories.forumHub,
                        )
                    },
                )
                ForumThreadsRoute(
                    viewModel = forumViewModel,
                    mediaLoadingPolicy = settings.reading.mediaLoading,
                    callbacks = ForumThreadsCallbacks(
                        capabilities = ForumThreadsCapabilities(
                            canCreateThread = settings.postingEnabled,
                        ),
                        onBack = { navController.popBackStack() },
                        onSearch = { navController.navigate("search/forum/${Uri.encode(it.name)}") },
                        onCreateThread = { value ->
                            openComposer(
                                ContentSubmissionTarget(
                                    kind = ContentSubmissionKind.NewThread,
                                    forumId = value.id,
                                    forumName = value.name.removeSuffix("吧"),
                                ),
                            )
                        },
                        onOpenThread = {
                            container.featureRepositories.rememberThreadSummary(it)
                            navController.navigate(threadRoute(it.id))
                        },
                        onOpenUser = { navController.navigate(userRoute(it)) },
                        onBlockForum = { forum ->
                            scope.launch {
                                runCatching { container.addBlockedForum(forum) }
                                    .onFailure { message = readableMessage(it) }
                            }
                        },
                        onOpenMedia = openThreadMedia,
                    ),
                )
            }

            composable(Routes.Thread) { entry ->
                val threadId = entry.arguments?.getString("threadId")?.toLongOrNull() ?: return@composable
                var isSavingThread by remember(threadId) { mutableStateOf(false) }
                var showsSaveMode by remember(threadId) { mutableStateOf(false) }
                val isThreadSaved = savedThreadEntries.any { it.threadId == threadId }
                val initialPostId = entry.arguments?.getString("postId")?.toULongOrNull()?.takeIf { it > 0uL }
                val initialDestination = when (entry.arguments?.getString("initialDestination")) {
                    "replies" -> ThreadInitialDestination.Replies
                    else -> null
                }
                ThreadRoute(
                    threadId = threadId,
                    initialPostId = initialPostId,
                    initialDestination = initialDestination,
                    viewModelSessionKey = accountSessionKey,
                    repository = container.featureRepositories.thread,
                    capabilities = ThreadCapabilities(
                        canReply = settings.replyingEnabled,
                        canLike = settings.likingEnabled,
                    ),
                    onBack = { navController.popBackStack() },
                    onForumClick = { navController.navigate(forumRoute(it)) },
                    onReply = { reply ->
                        val target = container.featureRepositories.contentSubmissionTarget(threadId, reply)
                        if (target == null) message = "帖子信息尚未加载完整，请刷新后重试。" else openComposer(target)
                    },
                    onUserClick = { navController.navigate(userRoute(UserSummary(it, "", "", ""))) },
                    onLinkClick = { openPublicLink(context, it) },
                    onShare = { sharePublicLink(context, it) },
                    onSave = {
                        if (!isSavingThread) showsSaveMode = true
                    },
                    isSaving = isSavingThread,
                    isSaved = isThreadSaved,
                    onDownloadImage = requestImageDownload,
                    onSaveImage = saveImageAction,
                    readingPreferences = settings.reading,
                )
                if (showsSaveMode) {
                    SavedThreadSaveModeDialog(
                        onDismiss = { showsSaveMode = false },
                        onSelect = { mode ->
                            showsSaveMode = false
                            isSavingThread = true
                            scope.launch {
                                runCatching { container.savedThreads.save(threadId, mode) }
                                    .onSuccess {
                                        message = "已保存主楼、${it.replyCount} 层回复和 ${it.subpostCount} 条楼中楼（${savedThreadMediaModeLabel(it.mediaMode)}）。"
                                    }
                                    .onFailure { message = readableMessage(it) }
                                isSavingThread = false
                            }
                        },
                    )
                }
            }

            composable(Routes.Login) {
                val login: LoginViewModel = viewModel(factory = factory { LoginViewModel(container.accountFeatures.login) })
                LoginRoute(
                    viewModel = login,
                    onLoggedIn = {
                        if (!navController.popBackStack()) onSelectRoot(RootDestination.Me)
                    },
                    onError = { message = it },
                )
            }

            composable(Routes.User) { entry ->
                val user = userFrom(entry)
                if (user == null) {
                    MissingStatePage("用户信息无效，请返回后重试", navController::popBackStack)
                } else {
                    val profile: UserProfileViewModel = viewModel(
                        key = "user-${user.id}-${user.name}-$accountSessionKey",
                        factory = factory { UserProfileViewModel(user, container.accountFeatures.userProfile) },
                    )
                    UserProfileRoute(
                        viewModel = profile,
                        onBack = { navController.popBackStack() },
                        onEditProfile = {
                            container.stageProfileEdit(it)
                            navController.navigate(Routes.EditProfile)
                        },
                        onOpenRelationship = { following ->
                            navController.navigate(
                                relationshipRoute(
                                    user,
                                    if (following) UserRelationshipKind.Following else UserRelationshipKind.Followers,
                                ),
                            )
                        },
                        onOpenForum = { navController.navigate(forumRoute(it)) },
                        onOpenThread = {
                            container.featureRepositories.rememberThreadSummary(it)
                            navController.navigate(threadRoute(it.id))
                        },
                    )
                }
            }

            composable(Routes.Relationships) { entry ->
                val user = userFrom(entry) ?: return@composable
                val kind = entry.arguments?.getString("kind")?.let {
                    runCatching { UserRelationshipKind.valueOf(it) }.getOrNull()
                } ?: return@composable
                val relationships: UserRelationshipsViewModel = viewModel(
                    key = "relationships-${user.id}-${kind.name}-$accountSessionKey",
                    factory = factory {
                        UserRelationshipsViewModel(user, kind, container.accountFeatures.relationships)
                    },
                )
                UserRelationshipsRoute(
                    viewModel = relationships,
                    title = if (kind == UserRelationshipKind.Following) "关注的用户" else "粉丝",
                    onBack = { navController.popBackStack() },
                    onOpenUser = { navController.navigate(userRoute(it)) },
                )
            }

            composable(Routes.Messages) {
                val messages: MessagesViewModel = viewModel(
                    key = "messages-$accountSessionKey",
                    factory = factory { MessagesViewModel(account, container.accountFeatures.messages) },
                )
                MessagesRoute(
                    viewModel = messages,
                    onBack = { navController.popBackStack() },
                    onLogin = { navController.navigate(Routes.Login) },
                    onOpenMessage = { item ->
                        item.threadId?.let { navController.navigate(threadRoute(it, item.postId)) }
                            ?: run { message = "这条消息没有可打开的帖子。" }
                    },
                )
            }

            composable(Routes.Favorites) {
                val favorites: ThreadFavoritesViewModel = viewModel(
                    key = "favorites-$accountSessionKey",
                    factory = factory { ThreadFavoritesViewModel(account, container.accountFeatures.threadFavorites) },
                )
                ThreadFavoritesRoute(
                    viewModel = favorites,
                    onBack = { navController.popBackStack() },
                    onLogin = { navController.navigate(Routes.Login) },
                    onOpenFavorite = { navController.navigate(threadRoute(it.threadId, it.markedPostId)) },
                )
            }

            composable(Routes.History) {
                val history: BrowsingHistoryViewModel = viewModel(factory = factory {
                    BrowsingHistoryViewModel(container.accountFeatures.browsingHistory)
                })
                BrowsingHistoryRoute(
                    viewModel = history,
                    onBack = { navController.popBackStack() },
                    onOpenEntry = {
                        container.featureRepositories.rememberThreadSummary(it.thread)
                        navController.navigate(threadRoute(it.thread.id))
                    },
                )
            }

            composable(Routes.SavedThreads) {
                SavedThreadsRoute(
                    repository = container.savedThreads,
                    onBack = { navController.popBackStack() },
                    onOpen = { navController.navigate("saved-thread/$it") },
                )
            }

            composable(Routes.SavedThread) { entry ->
                val threadId = entry.arguments?.getString("threadId")?.toLongOrNull() ?: return@composable
                SavedThreadDetailRoute(
                    threadId = threadId,
                    repository = container.savedThreads,
                    readingPreferences = settings.reading,
                    onBack = { navController.popBackStack() },
                    onForumClick = { navController.navigate(forumRoute(it)) },
                    onUserClick = { navController.navigate(userRoute(UserSummary(it, "", "", ""))) },
                    onLinkClick = { openPublicLink(context, it) },
                    onShare = { sharePublicLink(context, "https://tieba.baidu.com/p/$threadId") },
                    onDownloadImage = requestImageDownload,
                    onSaveImage = saveImageAction,
                )
            }

            composable(Routes.EditProfile) {
                val pending = container.profilePendingEdit
                val ownsPendingProfile = account?.uid?.toLongOrNull() == pending?.user?.id
                if (pending == null || !ownsPendingProfile) {
                    MissingStatePage("需要重新进入个人主页后再编辑资料", navController::popBackStack)
                } else {
                    val edit: EditProfileViewModel = viewModel(
                        key = "edit-profile-${pending.user.id}-$accountSessionKey",
                        factory = factory { EditProfileViewModel(pending, container.accountFeatures.profileEdit) },
                    )
                    EditProfileRoute(
                        viewModel = edit,
                        onBack = { navController.popBackStack() },
                        onSaved = {
                            container.stageProfileEdit(null)
                            navController.popBackStack()
                        },
                    )
                }
            }

            composable(Routes.Settings) {
                BackPage("设置", navController::popBackStack) {
                    SettingsRoute(
                        viewModel = settingsViewModel,
                        host = SettingsHostState(account, versionName),
                        onOpen = { destination ->
                            navController.navigate(
                                when (destination) {
                                    SettingsDestination.Reading -> Routes.Reading
                                    SettingsDestination.SavedThreads -> Routes.SavedThreads
                                    SettingsDestination.Blocklist -> Routes.Blocklist
                                    SettingsDestination.About -> Routes.About
                                },
                            )
                        },
                    )
                }
            }
            composable(Routes.Reading) {
                BackPage("阅读设置", navController::popBackStack) { ReadingSettingsRoute(settingsViewModel) }
            }
            composable(Routes.Blocklist) {
                BackPage("屏蔽设置", navController::popBackStack) { BlocklistSettingsRoute(settingsViewModel) }
            }
            composable(Routes.About) {
                BackPage("关于", navController::popBackStack) {
                    AboutSettingsScreen(
                        info = SettingsAboutInfo(versionName),
                        onOpenUrl = { openPublicLink(context, it) },
                    )
                }
            }

            composable(Routes.Composer) { entry ->
                val target = composerTargetFrom(entry)
                val current = account
                when {
                    target == null -> MissingStatePage("发布目标无效，请返回后重试", navController::popBackStack)
                    current == null -> MissingStatePage("请先登录贴吧账号", navController::popBackStack)
                    else -> {
                        val composer: ComposerViewModel = viewModel(
                            key = "composer-${composerRoute(target)}-$accountSessionKey",
                            factory = ComposerViewModel.factory(
                                accountId = current.id,
                                target = target,
                                repository = container.composerRepository,
                                riskAcknowledged = settings.submissionRiskAcknowledged,
                            ),
                        )
                        ContentComposerRoute(
                            viewModel = composer,
                            submissionCapability = ComposerSubmissionCapability.fromSettings(
                                kind = target.kind,
                                enabled = contentSubmissionEnabled(
                                    target.kind,
                                    settings.postingEnabled,
                                    settings.replyingEnabled,
                                ),
                            ),
                            onCancel = { navController.popBackStack() },
                            onAcknowledgeRisk = {
                                scope.launch { container.settingsRepository.acknowledgeSubmissionRisk() }
                            },
                            onSent = { receipt ->
                                navController.popBackStack()
                                if (target.kind == ContentSubmissionKind.NewThread) {
                                    navController.navigate(threadRoute(receipt.threadId))
                                } else {
                                    message = "已提交，请刷新帖子确认显示结果。"
                                }
                            },
                        )
                    }
                }
            }
        }

        message?.let { currentMessage ->
            AlertDialog(
                onDismissRequest = { message = null },
                title = { Text("提示") },
                text = { Text(currentMessage) },
                confirmButton = { TextButton(onClick = { message = null }) { Text("好") } },
            )
        }
        mediaPreviewAction?.let { action ->
            ThreadMediaPreviewDialog(
                action = action,
                onDismiss = { mediaPreviewAction = null },
                onDownloadImage = requestImageDownload,
                saveImageAction = saveImageAction,
            )
        }
    }
}

@Composable
private fun SearchPage(
    navController: NavHostController,
    repository: dev.infinityf4p.tiebapure.feature.search.SearchRepository,
    scope: SearchScope,
    initialKeyword: String,
    rememberThreadSummary: (dev.infinityf4p.tiebapure.core.model.ThreadSummary) -> Unit,
    mediaLoadingPolicy: dev.infinityf4p.tiebapure.core.model.ReaderMediaLoadingPolicy,
    onOpenMedia: (dev.infinityf4p.tiebapure.core.model.ThreadSummary, Int) -> Unit,
) {
    val search: SearchViewModel = viewModel(
        key = "search-${(scope as? SearchScope.ForumOnly)?.forum?.name.orEmpty()}-$initialKeyword",
        factory = factory { SearchViewModel(repository, scope, initialKeyword) },
    )
    SearchRoute(
        viewModel = search,
        mediaLoadingPolicy = mediaLoadingPolicy,
        callbacks = SearchCallbacks(
            onBack = { navController.popBackStack() },
            onOpenThread = {
                dispatchSearchThreadNavigation(it, rememberThreadSummary) { route ->
                    navController.navigate(route)
                }
            },
            onOpenUser = { navController.navigate(userRoute(it)) },
            onOpenForum = { navController.navigate(forumRoute(it)) },
            onOpenMedia = onOpenMedia,
        ),
    )
}

@Composable
private fun BackPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun MissingStatePage(message: String, onBack: () -> Unit) {
    BackPage("无法继续", onBack) {
        ReaderStatePane(ReaderState.Empty(message), Modifier.fillMaxSize())
    }
}

@Composable
private fun RootNavigationBar(selected: RootDestination, onSelected: (RootDestination) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        for (destination in RootDestination.entries) {
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelected(destination) },
                icon = { Icon(rootIcon(destination), contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun RootNavigationRail(
    selected: RootDestination,
    onSelected: (RootDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        for (destination in RootDestination.entries) {
            NavigationRailItem(
                selected = selected == destination,
                onClick = { onSelected(destination) },
                icon = { Icon(rootIcon(destination), contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun NavHostController.navigateToRoot(destination: RootDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun EmptyDetailPane() {
    ReaderStatePane(ReaderState.Empty("暂无选中内容"), Modifier.fillMaxSize())
}

private fun rootIcon(destination: RootDestination) = when (destination) {
    RootDestination.Home -> Icons.Outlined.Home
    RootDestination.Forums -> Icons.Outlined.GridView
    RootDestination.Me -> Icons.Outlined.Person
}

private fun forumRoute(forum: Forum): String =
    checkNotNull(buildForumRoute(forum, Uri::encode)) { "贴吧名称不能为空。" }
private fun threadRoute(
    threadId: Long,
    postId: ULong? = null,
    initialDestination: ThreadInitialDestination? = null,
): String = buildThreadRoute(threadId, postId, initialDestination)

private fun userRoute(user: UserSummary): String =
    buildUserRoute(user, Uri::encode)

private fun relationshipRoute(user: UserSummary, kind: UserRelationshipKind): String =
    "relationships/${user.id}/${Uri.encode(user.resolvedDisplayName)}/${kind.name}"

private fun userFrom(entry: NavBackStackEntry): UserSummary? {
    val id = entry.arguments?.getString("userId")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val displayName = Uri.decode(entry.arguments?.getString("userName").orEmpty()).ifBlank { "用户$id" }
    return UserSummary(id, displayName, displayName, "")
}

private fun composerRoute(target: ContentSubmissionTarget): String = listOf(
    "compose",
    target.kind.name,
    target.forumId.toString(),
    Uri.encode(target.forumName),
    (target.threadId ?: 0).toString(),
    (target.parentPostId ?: 0u).toString(),
    (target.parentFloor ?: 0).toString(),
    (target.subpostId ?: 0u).toString(),
    (target.replyUser?.id ?: 0).toString(),
    Uri.encode(target.replyUser?.resolvedDisplayName.orEmpty().ifBlank { "-" }),
).joinToString("/")

private fun composerTargetFrom(entry: NavBackStackEntry): ContentSubmissionTarget? {
    val arguments = entry.arguments ?: return null
    val kind = arguments.getString("kind")?.let { raw ->
        ContentSubmissionKind.entries.firstOrNull { it.name == raw }
    } ?: return null
    val forumId = arguments.getString("forumId")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val forumName = Uri.decode(arguments.getString("forumName").orEmpty()).takeIf(String::isNotBlank) ?: return null
    val replyUserId = arguments.getString("replyUserId")?.toLongOrNull()?.takeIf { it > 0 }
    val replyName = Uri.decode(arguments.getString("replyUserName").orEmpty()).takeUnless { it == "-" }.orEmpty()
    return ContentSubmissionTarget(
        kind = kind,
        forumId = forumId,
        forumName = forumName,
        threadId = arguments.getString("threadId")?.toLongOrNull()?.takeIf { it > 0 },
        parentPostId = arguments.getString("parentPostId")?.toULongOrNull()?.takeIf { it > 0uL },
        parentFloor = arguments.getString("parentFloor")?.toIntOrNull()?.takeIf { it > 0 },
        subpostId = arguments.getString("subpostId")?.toULongOrNull()?.takeIf { it > 0uL },
        replyUser = replyUserId?.let { UserSummary(it, replyName, replyName, "") },
    )
}

private fun factory(create: () -> ViewModel): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return create() as T
    }
}

private suspend fun AppContainer.addBlockedForum(forum: Forum) {
    val value = forum.name.trim().removeSuffix("吧").trim()
    if (value.isEmpty()) return
    settingsRepository.addBlocklistEntry(
        dev.infinityf4p.tiebapure.core.model.BlocklistEntry(
            kind = dev.infinityf4p.tiebapure.core.model.BlocklistEntryKind.Forum,
            value = value,
            numericId = forum.id.takeIf { it > 0 },
        ),
    )
}

private fun Account.toUserSummary(): UserSummary = UserSummary(
    id = uid.toLongOrNull() ?: 0,
    name = name,
    displayName = displayName,
    portrait = portrait,
)

private fun appVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull().orEmpty().ifBlank { "未知" }

private fun readableMessage(error: Throwable): String =
    error.message?.takeIf(String::isNotBlank) ?: "操作失败，请稍后重试。"

private fun openPublicLink(context: Context, rawUrl: String) {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return
    if (uri.scheme != "https") return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

private fun sharePublicLink(context: Context, rawUrl: String) {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return
    if (uri.scheme != "https" || uri.host != "tieba.baidu.com") return
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, uri.toString())
    }
    runCatching {
        context.startActivity(Intent.createChooser(sendIntent, "分享帖子"))
    }
}

private suspend fun saveImageToMediaStore(
    context: Context,
    downloader: SecureImageDownloadClient,
    image: ImageContent,
    onProgress: (Float) -> Unit = {},
) = withContext(Dispatchers.IO) {
    val url = image.originalUrl ?: image.thumbnailUrl ?: error("图片地址不可用。")
    require(MediaUrlPolicy.isAllowed(url) || OfflineMediaPolicy.resolve(url) != null) { "图片地址不受信任。" }
    onProgress(0f)
    downloader.download(url) { progress ->
        onProgress(progress.coerceIn(0f, 1f) * 0.9f)
    }.use { downloaded ->
        val extension = when (downloaded.mimeType.lowercase()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "TiebaPure-${System.currentTimeMillis()}.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, downloaded.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TiebaPure")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val destination = resolver.insert(collection, values) ?: error("无法创建相册文件。")
        try {
            resolver.openOutputStream(destination, "w")?.use { output ->
                downloaded.file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val copyFraction = if (downloaded.byteCount > 0) {
                            copied.toFloat() / downloaded.byteCount.toFloat()
                        } else {
                            1f
                        }
                        onProgress(0.9f + copyFraction.coerceIn(0f, 1f) * 0.1f)
                    }
                }
            } ?: error("无法写入相册文件。")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(destination, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            onProgress(1f)
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
    }
}
