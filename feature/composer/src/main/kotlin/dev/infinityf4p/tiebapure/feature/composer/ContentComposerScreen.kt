package dev.infinityf4p.tiebapure.feature.composer

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionImage
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionPolicy
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionReceipt
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionTarget
import dev.infinityf4p.tiebapure.core.model.TiebaEmoticon
import dev.infinityf4p.tiebapure.core.model.TiebaEmoticonEntry
import dev.infinityf4p.tiebapure.core.media.RemoteEmoticonImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ContentComposerRoute(
    viewModel: ComposerViewModel,
    submissionCapability: ComposerSubmissionCapability,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit,
    onAcknowledgeRisk: () -> Unit,
    onSent: (ContentSubmissionReceipt) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isReadingImages by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ContentSubmissionPolicy.maximumImages),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isReadingImages = true
            val remaining = ContentSubmissionPolicy.maximumImages - state.images.size
            val results = withContext(Dispatchers.IO) {
                uris.take(remaining).map { uri -> runCatching { readSubmissionImage(context.contentResolver, uri) } }
            }
            viewModel.addImages(results.mapNotNull(Result<ContentSubmissionImage>::getOrNull))
            imageError = results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
            isReadingImages = false
        }
    }

    LaunchedEffect(state.submission, state.draftCleanupWarning) {
        if (state.draftCleanupWarning == null) {
            (state.submission as? ComposerSubmissionState.Sent)?.let { onSent(it.receipt) }
        }
    }
    LaunchedEffect(state.closeAfterDraftSave) {
        if (state.closeAfterDraftSave) {
            viewModel.consumeDraftCloseRequest()
            onCancel()
        }
    }
    LaunchedEffect(submissionCapability) {
        viewModel.updateSubmissionCapability(submissionCapability)
    }
    ContentComposerScreen(
        state = state,
        submissionCapability = submissionCapability,
        modifier = modifier,
        isReadingImages = isReadingImages,
        onTitleChange = viewModel::updateTitle,
        onBodyChange = viewModel::updateBody,
        onPickImages = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onRemoveImage = viewModel::removeImage,
        onSaveDraft = { viewModel.saveDraft() },
        onSaveDraftAndClose = { viewModel.saveDraft(closeAfterSave = true) },
        onRestoreDraft = viewModel::restoreDraft,
        onDeleteDraft = viewModel::deleteDraft,
        onSend = { viewModel.requestSend(submissionCapability) },
        onConfirmRisk = {
            if (submissionCapability.canSubmit) {
                onAcknowledgeRisk()
                viewModel.confirmRiskAndSend(submissionCapability)
            }
        },
        onDismissRisk = viewModel::dismissRiskConfirmation,
        onConfirmOutcomeChecked = viewModel::confirmOutcomeChecked,
        onDismissError = viewModel::dismissError,
        onAcknowledgeDraftCleanupWarning = viewModel::acknowledgeDraftCleanupWarning,
        onCancel = onCancel,
    )
    imageError?.let { message ->
        AlertDialog(
            onDismissRequest = { imageError = null },
            title = { Text("无法添加图片") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { imageError = null }) { Text("好") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentComposerScreen(
    state: ComposerUiState,
    submissionCapability: ComposerSubmissionCapability = ComposerSubmissionCapability.Enabled,
    modifier: Modifier = Modifier,
    isReadingImages: Boolean = false,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onPickImages: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onSaveDraft: () -> Unit,
    onSaveDraftAndClose: () -> Unit,
    onRestoreDraft: (ComposerDraft) -> Unit,
    onDeleteDraft: (ComposerDraft) -> Unit,
    onSend: () -> Unit,
    onConfirmRisk: () -> Unit,
    onDismissRisk: () -> Unit,
    onConfirmOutcomeChecked: () -> Unit,
    onDismissError: () -> Unit,
    onAcknowledgeDraftCleanupWarning: () -> Unit,
    onCancel: () -> Unit,
) {
    var showDrafts by remember { mutableStateOf(false) }
    var showEmoticons by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val requestCancel = {
        when {
            !state.canExit -> Unit
            state.hasContent -> confirmCancel = true
            else -> onCancel()
        }
    }
    BackHandler(onBack = requestCancel)
    val canSend = !state.isBusy && !isReadingImages &&
        submissionCapability.canSubmit &&
        state.submission is ComposerSubmissionState.Idle &&
        validatedRequest(state.target, state.title, state.body, state.images) != null

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(composerTitle(state.target.kind), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = requestCancel, enabled = state.canExit) {
                        Icon(Icons.Outlined.Close, contentDescription = "取消")
                    }
                },
                actions = {
                    IconButton(onClick = { showDrafts = true }, enabled = state.drafts.isNotEmpty() && !state.isBusy) {
                        Icon(Icons.Outlined.History, contentDescription = "草稿列表")
                    }
                    TextButton(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier.testTag("composer-send"),
                    ) {
                        if (state.submission is ComposerSubmissionState.Sending) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("发送")
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        bottomBar = {
            ComposerActionBar(
                allowsImages = state.allowsImages,
                imageCount = state.images.size,
                isReadingImages = isReadingImages,
                emoticonsExpanded = showEmoticons,
                enabled = !state.isBusy && !isReadingImages,
                canSaveDraft = state.hasContent && !state.isBusy && !isReadingImages,
                onPickImages = onPickImages,
                onToggleEmoticons = {
                    focusManager.clearFocus()
                    showEmoticons = !showEmoticons
                },
                onSaveDraft = onSaveDraft,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(targetPrompt(state.target), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            submissionCapability.resolvedUnavailableReason(state.target.kind)?.let { reason ->
                item {
                    StatusText(reason, modifier = Modifier.testTag("composer-submission-unavailable"))
                }
            }
            if (state.target.kind == ContentSubmissionKind.NewThread) {
                item {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = onTitleChange,
                        label = { Text("标题") },
                        supportingText = { Text("${state.title.length}/${ContentSubmissionPolicy.maximumTitleCharacters}") },
                        isError = state.title.length > ContentSubmissionPolicy.maximumTitleCharacters,
                        singleLine = true,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = state.body,
                    onValueChange = onBodyChange,
                    label = { Text(if (state.target.kind == ContentSubmissionKind.NewThread) "正文" else "回复内容") },
                    supportingText = { Text("${state.body.length}/${ContentSubmissionPolicy.maximumBodyCharacters}") },
                    isError = state.body.length > ContentSubmissionPolicy.maximumBodyCharacters,
                    minLines = 8,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showEmoticons) {
                item {
                    EmoticonPicker(
                        enabled = !state.isBusy && !isReadingImages,
                        onDismiss = { showEmoticons = false },
                        onSelect = { entry ->
                            onBodyChange(
                                appendEmoticonToken(
                                    text = state.body,
                                    token = entry.token,
                                    maximumLength = ContentSubmissionPolicy.maximumBodyCharacters,
                                ),
                            )
                        },
                    )
                }
            }
            if (state.allowsImages) {
                if (state.images.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("图片", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${state.images.size}/${ContentSubmissionPolicy.maximumImages}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.images.size, key = { it }) { index ->
                                val image = state.images[index]
                                ComposerImage(image, onRemove = { onRemoveImage(index) })
                            }
                        }
                    }
                }
                item {
                    Text(
                        "最多 9 张；单张不超过 10 MiB，最长边不超过 20000 像素。发布新主题当前仅支持文字。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { SubmissionStatus(state.submission) }
            state.draftMessage?.let { message -> item { StatusText(message) } }
        }
    }

    if (state.showRiskConfirmation && submissionCapability.canSubmit) {
        RiskConfirmationDialog(onConfirm = onConfirmRisk, onDismiss = onDismissRisk)
    }
    (state.submission as? ComposerSubmissionState.VerificationRequired)?.let { verification ->
        VerificationDialog(
            challenge = verification.challenge,
            onCancel = onDismissError,
        )
    }
    (state.submission as? ComposerSubmissionState.OutcomeUnknown)?.let { unknown ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("发送结果无法确认") },
            text = { Text("${unknown.message}\n\n服务器可能已经收到内容。应用不会自动重发，请先返回帖子或吧页刷新核对。") },
            confirmButton = { TextButton(onClick = onConfirmOutcomeChecked) { Text("已刷新核对") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("关闭编辑器") } },
        )
    }
    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("无法继续") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("好") } },
        )
    }
    state.draftCleanupWarning?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("内容已发送") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onAcknowledgeDraftCleanupWarning) { Text("知道了") }
            },
        )
    }
    if (showDrafts) {
        DraftListDialog(
            drafts = state.drafts,
            onRestore = { onRestoreDraft(it); showDrafts = false },
            onDelete = onDeleteDraft,
            onDismiss = { showDrafts = false },
        )
    }
    if (confirmCancel && state.canExit) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("保存未发送的更改？") },
            text = { Text("可以先保存草稿，之后从相同帖子或回复位置恢复。") },
            confirmButton = {
                TextButton(
                    onClick = { onSaveDraftAndClose(); confirmCancel = false },
                    enabled = !isReadingImages,
                ) { Text("保存草稿并关闭") }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("放弃更改") } },
        )
    }
}

@Composable
private fun EmoticonPicker(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSelect: (TiebaEmoticonEntry) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("表情", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "收起表情")
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            items(TiebaEmoticon.catalog.chunked(2), key = { it.first().imageName }) { entries ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entries.forEach { entry ->
                        IconButton(
                            onClick = { onSelect(entry) },
                            enabled = enabled,
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                        ) {
                            RemoteEmoticonImage(
                                code = entry.imageName,
                                modifier = Modifier.size(34.dp),
                                contentDescription = "插入${entry.name}表情",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerActionBar(
    allowsImages: Boolean,
    imageCount: Int,
    isReadingImages: Boolean,
    emoticonsExpanded: Boolean,
    enabled: Boolean,
    canSaveDraft: Boolean,
    onPickImages: () -> Unit,
    onToggleEmoticons: () -> Unit,
    onSaveDraft: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (allowsImages) {
                    TextButton(
                        onClick = onPickImages,
                        enabled = enabled && imageCount < ContentSubmissionPolicy.maximumImages,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        if (isReadingImages) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (imageCount > 0) "图片 $imageCount" else "图片")
                    }
                }
                TextButton(
                    onClick = onToggleEmoticons,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        Icons.Outlined.Mood,
                        contentDescription = null,
                        tint = if (emoticonsExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("表情")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSaveDraft, enabled = canSaveDraft, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("存草稿")
                }
            }
        }
    }
}

internal fun appendEmoticonToken(
    text: String,
    token: String,
    maximumLength: Int,
): String {
    if (maximumLength <= 0 || token.isEmpty()) return text
    val separator = if (text.isNotEmpty() && !text.last().isWhitespace()) " " else ""
    val candidate = text + separator + token
    return candidate.takeIf { it.length <= maximumLength } ?: text
}

@Composable
private fun SubmissionStatus(state: ComposerSubmissionState) {
    when (state) {
        ComposerSubmissionState.Idle -> Unit
        ComposerSubmissionState.Sending -> StatusText("正在发送。完成前请勿重复操作。")
        is ComposerSubmissionState.Sent -> StatusText("发送成功")
        is ComposerSubmissionState.VerificationRequired -> StatusText("贴吧要求额外安全验证，内容未再次发送。")
        is ComposerSubmissionState.OutcomeUnknown -> Unit
        is ComposerSubmissionState.Failed -> StatusText(state.message)
    }
}

@Composable
private fun RiskConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("实验性发布功能") },
        text = {
            Text("TiebaPure 通过非官方实验接口发帖和回复。使用时可能触发贴吧风控，导致内容被隐藏或删除、账号功能受限；极端情况下账号可能被冻结。若发送结果无法确认，应用不会自动重发，请先刷新页面核对。")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("了解并继续") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VerificationDialog(
    challenge: dev.infinityf4p.tiebapure.core.model.SubmissionVerificationChallenge,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("需要安全验证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(challenge.message)
                Text(
                    "当前版本暂不支持在应用内完成这项验证。本次内容没有再次发送，请稍后再试或改用贴吧官方客户端完成验证。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("知道了") } },
    )
}

@Composable
private fun DraftListDialog(
    drafts: List<ComposerDraft>,
    onRestore: (ComposerDraft) -> Unit,
    onDelete: (ComposerDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Drafts, contentDescription = null) },
        title = { Text("草稿") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(drafts, key = { "${it.accountId}:${it.targetKey}" }) { draft ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(draftTitle(draft), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                draft.body.ifBlank { "空草稿" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (draft.storedImageCount > 0) {
                                Text(
                                    "${draft.storedImageCount} 张图片",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = { onRestore(draft) }) { Text("恢复") }
                        IconButton(onClick = { onDelete(draft) }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除草稿")
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ComposerImage(image: ContentSubmissionImage, onRemove: () -> Unit) {
    val bitmap = remember(image) { decodeThumbnail(image.bytes)?.asImageBitmap() }
    Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))) {
        bitmap?.let {
            Image(it, contentDescription = "待发送图片", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除图片")
        }
    }
}

private fun decodeThumbnail(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) })
}

@Composable
private fun StatusText(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
}

internal fun validatedRequest(
    target: ContentSubmissionTarget,
    title: String,
    body: String,
    images: List<ContentSubmissionImage>,
): ContentSubmissionRequest? = runCatching {
    ContentSubmissionPolicy.validate(ContentSubmissionRequest(target, title, body, images))
}.getOrNull()

private fun readSubmissionImage(resolver: ContentResolver, uri: Uri): ContentSubmissionImage {
    val mime = resolver.getType(uri)?.trim()?.lowercase() ?: "application/octet-stream"
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1_024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > ContentSubmissionPolicy.maximumImageBytes) throw IllegalArgumentException("单张图片不能超过 10 MiB。")
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: throw IllegalArgumentException("无法读取图片。")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
        bounds.outWidth > ContentSubmissionPolicy.maximumPixelDimension ||
        bounds.outHeight > ContentSubmissionPolicy.maximumPixelDimension ||
        bounds.outWidth.toLong() * bounds.outHeight > ContentSubmissionPolicy.maximumPixelCount
    ) throw IllegalArgumentException("图片尺寸无效或过大。")
    val image = ContentSubmissionImage(bytes, mime)
    ContentSubmissionPolicy.validate(
        ContentSubmissionRequest(
            target = ContentSubmissionTarget(ContentSubmissionKind.ThreadReply, 1, "validation", threadId = 1),
            body = "validation",
            images = listOf(image),
        ),
    )
    return image
}

private fun composerTitle(kind: ContentSubmissionKind): String = when (kind) {
    ContentSubmissionKind.NewThread -> "发布主题"
    ContentSubmissionKind.ThreadReply -> "回复帖子"
    ContentSubmissionKind.PostReply -> "回复楼层"
    ContentSubmissionKind.SubpostReply -> "回复楼中楼"
}

private fun targetPrompt(target: ContentSubmissionTarget): String = when (target.kind) {
    ContentSubmissionKind.NewThread -> "发布到 ${target.forumName}吧"
    ContentSubmissionKind.ThreadReply -> "回复当前帖子"
    ContentSubmissionKind.PostReply, ContentSubmissionKind.SubpostReply ->
        target.replyUser?.resolvedDisplayName?.let { "回复 $it" }
            ?: target.parentFloor?.takeIf { it > 0 }?.let { "回复第 $it 楼" }
            ?: "回复用户"
}

private fun draftTitle(draft: ComposerDraft): String = when (draft.target.kind) {
    ContentSubmissionKind.NewThread -> draft.title.ifBlank { "发布到 ${draft.target.forumName}吧" }
    ContentSubmissionKind.ThreadReply -> "回复帖子 · ${draft.target.forumName}吧"
    ContentSubmissionKind.PostReply -> "回复第 ${draft.target.parentFloor ?: "?"} 楼"
    ContentSubmissionKind.SubpostReply -> "回复楼中楼"
}
