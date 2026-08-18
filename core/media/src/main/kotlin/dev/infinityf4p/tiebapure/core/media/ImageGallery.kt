package dev.infinityf4p.tiebapure.core.media

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ZoomInMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import dev.infinityf4p.tiebapure.core.model.ImageContent
import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

typealias ImageSaveAction = suspend (ImageContent, (Float) -> Unit) -> Unit

sealed interface OriginalImageState {
    data object Preview : OriginalImageState
    data class Loading(val progress: Float) : OriginalImageState
    data object Ready : OriginalImageState
    data class Failed(val message: String?) : OriginalImageState
}

internal sealed interface GallerySaveState {
    data object Idle : GallerySaveState
    data class Saving(val progress: Float) : GallerySaveState
    data object Success : GallerySaveState
    data object Failed : GallerySaveState
}

private sealed interface GalleryImageLoadState {
    data class Loading(val progress: Float?) : GalleryImageLoadState
    data object Ready : GalleryImageLoadState
    data object Failed : GalleryImageLoadState
}

@Composable
fun ImageGallery(
    images: List<ImageContent>,
    initialPage: Int,
    onDismiss: () -> Unit,
    onDownload: (ImageContent) -> Unit = {},
    saveAction: ImageSaveAction? = null,
) {
    if (images.isEmpty()) return
    val safeInitialPage = ImageGalleryPolicy.clampedPage(initialPage, images.size)
    val context = LocalContext.current.applicationContext
    val loader = remember(context) { OriginalImageLoader(context) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = safeInitialPage) { images.size }
    val originalStates = remember(images) { mutableStateMapOf<Int, OriginalImageState>() }
    val originalFiles = remember(images) { mutableStateMapOf<Int, File>() }
    val pageScales = remember(images) { mutableStateMapOf<Int, Float>() }
    val saveStates = remember(images) { mutableStateMapOf<Int, GallerySaveState>() }
    val originalJobs = remember(images) { mutableMapOf<Int, Job>() }
    val saveJobs = remember(images) { mutableMapOf<Int, Job>() }
    var isClosing by remember { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    fun requestDismiss() {
        if (isClosing) return
        isClosing = true
    }

    LaunchedEffect(isClosing) {
        if (isClosing) {
            delay(ImageGalleryPolicy.dismissAnimationMillis)
            currentOnDismiss()
        }
    }

    DisposableEffect(images) {
        onDispose {
            originalJobs.values.forEach(Job::cancel)
            saveJobs.values.forEach(Job::cancel)
        }
    }

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        GalleryDialogWindowEffect()
        BackHandler(onBack = ::requestDismiss)
        val galleryAlpha by animateFloatAsState(
            targetValue = if (isClosing) 0f else 1f,
            animationSpec = tween(ImageGalleryPolicy.dismissAnimationMillis.toInt()),
            label = "image-gallery-alpha",
        )
        val galleryScale by animateFloatAsState(
            targetValue = if (isClosing) 0.98f else 1f,
            animationSpec = tween(ImageGalleryPolicy.dismissAnimationMillis.toInt()),
            label = "image-gallery-scale",
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .graphicsLayer {
                    alpha = galleryAlpha
                    scaleX = galleryScale
                    scaleY = galleryScale
                }
                .semantics { contentDescription = "图片浏览器" }
                .testTag("image-gallery"),
        ) {
            HorizontalPager(
                state = pagerState,
                key = { index -> ImageGalleryPolicy.pageKey(images[index], index) },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("image-gallery-pager"),
                verticalAlignment = Alignment.CenterVertically,
                userScrollEnabled = ImageGalleryGesturePolicy.pagerEnabled(
                    pageScales[pagerState.currentPage] ?: 1f,
                ),
            ) { page ->
                val image = images[page]
                ZoomableImage(
                    model = originalFiles[page] ?: ImageGalleryPolicy.previewUrl(image),
                    imageAspectRatio = image.aspectRatio.toFloat(),
                    contentDescription = "图片 ${page + 1}/${images.size}",
                    pageKey = ImageGalleryPolicy.pageKey(image, page),
                    modifier = Modifier.fillMaxSize(),
                    onScaleChanged = { pageScales[page] = it },
                    onDismiss = ::requestDismiss,
                )
            }

            val currentPage = pagerState.currentPage.coerceIn(images.indices)
            val currentImage = images[currentPage]
            val originalState = originalStates[currentPage] ?: OriginalImageState.Preview
            val saveState = saveStates[currentPage] ?: GallerySaveState.Idle
            val originalUrl = ImageGalleryPolicy.originalUrl(currentImage)
            val downloadableImage = ImageGalleryPolicy.downloadableImage(currentImage)

            GalleryTopBar(
                currentPage = currentPage,
                imageCount = images.size,
                sourceDescription = ImageGalleryPolicy.sourceDescription(
                    image = currentImage,
                    originalLoaded = originalState == OriginalImageState.Ready,
                ),
                onClose = ::requestDismiss,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            GalleryBottomBar(
                image = currentImage,
                originalAvailable = originalUrl != null,
                originalState = originalState,
                saveAvailable = downloadableImage != null,
                saveState = saveState,
                onRequestOriginal = {
                    if (originalUrl == null) return@GalleryBottomBar
                    if (originalJobs[currentPage]?.isActive == true) return@GalleryBottomBar
                    originalJobs[currentPage] = scope.launch {
                        originalStates[currentPage] = OriginalImageState.Loading(0f)
                        try {
                            val file = loader.load(originalUrl) { progress ->
                                scope.launch {
                                    if (originalStates[currentPage] is OriginalImageState.Loading) {
                                        originalStates[currentPage] = OriginalImageState.Loading(
                                            progress.coerceIn(0f, 1f),
                                        )
                                    }
                                }
                            }
                            originalFiles[currentPage] = file
                            originalStates[currentPage] = OriginalImageState.Ready
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            originalStates[currentPage] = OriginalImageState.Failed(null)
                        }
                    }
                },
                onSave = {
                    val safeImage = downloadableImage ?: return@GalleryBottomBar
                    if (saveJobs[currentPage]?.isActive == true) return@GalleryBottomBar
                    if (saveAction == null) {
                        onDownload(safeImage)
                    } else {
                        saveJobs[currentPage] = scope.launch {
                            saveStates[currentPage] = GallerySaveState.Saving(0f)
                            try {
                                saveAction(safeImage) { progress ->
                                    scope.launch {
                                        if (saveStates[currentPage] is GallerySaveState.Saving) {
                                            saveStates[currentPage] = GallerySaveState.Saving(
                                                progress.coerceIn(0f, 1f),
                                            )
                                        }
                                    }
                                }
                                saveStates[currentPage] = GallerySaveState.Success
                            } catch (error: Throwable) {
                                if (error is CancellationException) throw error
                                saveStates[currentPage] = GallerySaveState.Failed
                            }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun GalleryDialogWindowEffect() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }
}

@Composable
private fun GalleryTopBar(
    currentPage: Int,
    imageCount: Int,
    sourceDescription: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.66f))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(48.dp)
                .testTag("image-gallery-close"),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭图片浏览器", tint = Color.White)
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (imageCount > 1) {
                Text(
                    text = "${currentPage + 1} / $imageCount",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("image-gallery-page-indicator"),
                )
            }
            Text(
                text = sourceDescription,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("image-gallery-source"),
            )
        }
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun GalleryBottomBar(
    image: ImageContent,
    originalAvailable: Boolean,
    originalState: OriginalImageState,
    saveAvailable: Boolean,
    saveState: GallerySaveState,
    onRequestOriginal: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (saveState) {
            GallerySaveState.Success -> GalleryNotice(
                icon = Icons.Outlined.CheckCircle,
                message = "图片已保存到相册",
                tag = "image-gallery-save-success",
            )
            GallerySaveState.Failed -> GalleryNotice(
                icon = Icons.Outlined.ErrorOutline,
                message = "图片保存失败，请检查网络或存储权限后重试",
                tag = "image-gallery-save-failure",
            )
            GallerySaveState.Idle,
            is GallerySaveState.Saving,
            -> Unit
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OriginalImageButton(
                image = image,
                available = originalAvailable,
                state = originalState,
                onClick = onRequestOriginal,
                modifier = Modifier.weight(1f),
            )
            SaveImageButton(
                available = saveAvailable,
                state = saveState,
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun GalleryNotice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(message, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun OriginalImageButton(
    image: ImageContent,
    available: Boolean,
    state: OriginalImageState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = available && state !is OriginalImageState.Loading && state != OriginalImageState.Ready
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag("image-gallery-original"),
        shape = RoundedCornerShape(6.dp),
        color = Color.White.copy(alpha = if (enabled) 0.16f else 0.09f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (state is OriginalImageState.Loading) {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .matchParentSize(),
                    color = Color.White.copy(alpha = 0.28f),
                    trackColor = Color.Transparent,
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    !available -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp))
                    state is OriginalImageState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    state == OriginalImageState.Ready -> Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                    state is OriginalImageState.Failed -> Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    else -> Icon(Icons.Outlined.ZoomInMap, null, Modifier.size(18.dp))
                }
                Text(
                    text = ImageGalleryPolicy.originalButtonLabel(image, available, state),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SaveImageButton(
    available: Boolean,
    state: GallerySaveState,
    onClick: () -> Unit,
) {
    val saving = state is GallerySaveState.Saving
    IconButton(
        onClick = onClick,
        enabled = available && !saving,
        modifier = Modifier
            .size(48.dp)
            .testTag("image-gallery-save"),
    ) {
        when (state) {
            is GallerySaveState.Saving -> Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(30.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "${(state.progress.coerceIn(0f, 1f) * 100).toInt()}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            GallerySaveState.Success -> Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "再次保存图片",
                tint = Color.White,
            )
            GallerySaveState.Failed -> Icon(
                Icons.Outlined.Refresh,
                contentDescription = "重试保存图片",
                tint = Color.White,
            )
            GallerySaveState.Idle -> Icon(
                Icons.Outlined.Download,
                contentDescription = if (available) "保存图片" else "图片不可保存",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    model: Any?,
    imageAspectRatio: Float,
    contentDescription: String,
    pageKey: String,
    modifier: Modifier = Modifier,
    onScaleChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val loader = remember(context) { OriginalImageLoader(context) }
    val scope = rememberCoroutineScope()
    val currentOnScaleChanged by rememberUpdatedState(onScaleChanged)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var resolvedModel by remember(pageKey) { mutableStateOf<Any?>(null) }
    var loadState by remember(pageKey) { mutableStateOf<GalleryImageLoadState>(GalleryImageLoadState.Loading(null)) }
    var retryRequest by remember(pageKey) { mutableIntStateOf(0) }
    var scale by remember(pageKey) { mutableFloatStateOf(1f) }
    var translation by remember(pageKey) { mutableStateOf(Offset.Zero) }
    var viewport by remember(pageKey) { mutableStateOf(IntSize.Zero) }

    fun clamp(offset: Offset, targetScale: Float): Offset {
        val bounds = ImageGalleryGesturePolicy.panBounds(
            viewportWidth = viewport.width.toFloat(),
            viewportHeight = viewport.height.toFloat(),
            imageAspectRatio = imageAspectRatio,
            scale = targetScale,
        )
        return Offset(
            x = offset.x.coerceIn(-bounds.maximumX, bounds.maximumX),
            y = offset.y.coerceIn(-bounds.maximumY, bounds.maximumY),
        )
    }

    LaunchedEffect(model, retryRequest) {
        resolvedModel = null
        scale = 1f
        translation = Offset.Zero
        currentOnScaleChanged(scale)
        if (model == null) {
            loadState = GalleryImageLoadState.Failed
            return@LaunchedEffect
        }
        if (model !is String) {
            resolvedModel = model
            loadState = GalleryImageLoadState.Loading(null)
            return@LaunchedEffect
        }
        loadState = GalleryImageLoadState.Loading(0f)
        try {
            val file = loader.load(model) { progress ->
                scope.launch {
                    if (loadState is GalleryImageLoadState.Loading) {
                        loadState = GalleryImageLoadState.Loading(progress.coerceIn(0f, 1f))
                    }
                }
            }
            resolvedModel = file
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            loadState = GalleryImageLoadState.Failed
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val oldScale = scale
        val newScale = ImageGalleryGesturePolicy.clampedScale(scale * zoomChange)
        val scaleRatio = if (oldScale > 0f) newScale / oldScale else 1f
        val scaledTranslation = Offset(
            x = translation.x * scaleRatio,
            y = translation.y * scaleRatio,
        )
        scale = newScale
        currentOnScaleChanged(newScale)
        translation = if (ImageGalleryGesturePolicy.isZoomed(newScale)) {
            clamp(scaledTranslation + panChange, newScale)
        } else {
            Offset.Zero
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged {
                viewport = it
                translation = clamp(translation, scale)
            }
            .testTag("image-gallery-image")
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = translation.x
                translationY = translation.y
            }
            .transformable(
                state = transformState,
                canPan = { ImageGalleryGesturePolicy.isZoomed(scale) },
                lockRotationOnZoomPan = true,
            )
            .pointerInput(pageKey, viewport) {
                detectTapGestures(
                    onTap = { currentOnDismiss() },
                    onDoubleTap = { tap ->
                        val targetScale = ImageGalleryGesturePolicy.doubleTapScale(scale)
                        translation = if (ImageGalleryGesturePolicy.isZoomed(targetScale)) {
                            clamp(
                                ImageGalleryGesturePolicy.doubleTapTranslation(
                                    tapX = tap.x,
                                    tapY = tap.y,
                                    viewportWidth = viewport.width.toFloat(),
                                    viewportHeight = viewport.height.toFloat(),
                                    targetScale = targetScale,
                                ),
                                targetScale,
                            )
                        } else {
                            Offset.Zero
                        }
                        scale = targetScale
                        currentOnScaleChanged(scale)
                    },
                )
            }
            .semantics {
                this.contentDescription = contentDescription
                stateDescription = "缩放 ${(scale * 100).toInt()}%"
                onClick(label = "关闭图片浏览器") {
                    currentOnDismiss()
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction("放大图片") {
                        scale = ImageGalleryGesturePolicy.maximumScale.coerceAtMost(scale * 2f)
                        translation = clamp(translation, scale)
                        currentOnScaleChanged(scale)
                        true
                    },
                    CustomAccessibilityAction("缩小图片") {
                        scale = 1f
                        translation = Offset.Zero
                        currentOnScaleChanged(scale)
                        true
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (resolvedModel != null) {
            AsyncImage(
                model = resolvedModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onSuccess = { loadState = GalleryImageLoadState.Ready },
                onError = { loadState = GalleryImageLoadState.Failed },
            )
        }
        when (val state = loadState) {
            is GalleryImageLoadState.Loading -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    progress = { state.progress ?: 0f },
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
                state.progress?.let {
                    Text("${(it * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            GalleryImageLoadState.Failed -> Surface(
                onClick = { retryRequest += 1 },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("image-gallery-retry"),
                color = Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, tint = Color.White)
                    Text("图片加载失败，点击重试", color = Color.White)
                }
            }
            GalleryImageLoadState.Ready -> Unit
        }
    }
}

internal object ImageGalleryPolicy {
    const val dismissAnimationMillis = 140L

    fun clampedPage(page: Int, imageCount: Int): Int = when {
        imageCount <= 0 -> 0
        else -> page.coerceIn(0, imageCount - 1)
    }

    fun pageKey(image: ImageContent, index: Int): String =
        "$index|${image.originalUrl.orEmpty()}|${image.thumbnailUrl.orEmpty()}"

    fun previewUrl(image: ImageContent): String? =
        image.thumbnailUrl?.takeIf(MediaUrlPolicy::isAllowed)
            ?: image.originalUrl?.takeIf(MediaUrlPolicy::isAllowed)

    fun originalUrl(image: ImageContent): String? =
        image.originalUrl?.takeIf(MediaUrlPolicy::isAllowed)

    fun downloadableImage(image: ImageContent): ImageContent? {
        val url = originalUrl(image) ?: previewUrl(image) ?: return null
        return image.copy(originalUrl = url, thumbnailUrl = null)
    }

    fun sourceDescription(image: ImageContent, originalLoaded: Boolean): String {
        val sourceUrl = if (originalLoaded) originalUrl(image) else previewUrl(image)
        val parts = buildList {
            add(if (originalLoaded) "原图" else "预览")
            if (image.width > 0 && image.height > 0) add("${image.width} × ${image.height}")
            sourceUrl?.let(::safeHost)?.let { add(it) }
        }
        return parts.joinToString(" · ")
    }

    fun originalButtonLabel(
        image: ImageContent,
        available: Boolean,
        state: OriginalImageState,
    ): String = when {
        !available -> "无原图"
        state is OriginalImageState.Loading -> "${(state.progress.coerceIn(0f, 1f) * 100).toInt()}%"
        state == OriginalImageState.Ready -> "原图已加载"
        state is OriginalImageState.Failed -> "重试原图"
        else -> formatByteCount(image.originalSizeBytes)?.let { "查看原图 $it" } ?: "查看原图"
    }

    private fun safeHost(rawUrl: String): String? {
        if (!MediaUrlPolicy.isAllowed(rawUrl)) return null
        return runCatching { URI(rawUrl).host?.lowercase() }.getOrNull()
    }
}

internal data class GalleryPanBounds(val maximumX: Float, val maximumY: Float)

internal object ImageGalleryGesturePolicy {
    const val minimumScale = 1f
    const val maximumScale = 4f
    const val doubleTapTargetScale = 2f
    private const val zoomTolerance = 0.01f

    fun clampedScale(scale: Float): Float = scale.coerceIn(minimumScale, maximumScale)

    fun isZoomed(scale: Float): Boolean = clampedScale(scale) > minimumScale + zoomTolerance

    fun pagerEnabled(scale: Float): Boolean = !isZoomed(scale)

    fun doubleTapScale(currentScale: Float): Float =
        if (isZoomed(currentScale)) minimumScale else doubleTapTargetScale

    fun panBounds(
        viewportWidth: Float,
        viewportHeight: Float,
        imageAspectRatio: Float,
        scale: Float,
    ): GalleryPanBounds {
        if (viewportWidth <= 0f || viewportHeight <= 0f || !isZoomed(scale)) {
            return GalleryPanBounds(0f, 0f)
        }
        val ratio = imageAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val viewportRatio = viewportWidth / viewportHeight
        val fittedWidth: Float
        val fittedHeight: Float
        if (ratio >= viewportRatio) {
            fittedWidth = viewportWidth
            fittedHeight = viewportWidth / ratio
        } else {
            fittedHeight = viewportHeight
            fittedWidth = viewportHeight * ratio
        }
        return GalleryPanBounds(
            maximumX = ((fittedWidth * scale - viewportWidth) / 2f).coerceAtLeast(0f),
            maximumY = ((fittedHeight * scale - viewportHeight) / 2f).coerceAtLeast(0f),
        )
    }

    fun doubleTapTranslation(
        tapX: Float,
        tapY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        targetScale: Float,
    ): Offset = Offset(
        x = (viewportWidth / 2f - tapX) * (targetScale - 1f),
        y = (viewportHeight / 2f - tapY) * (targetScale - 1f),
    )
}
