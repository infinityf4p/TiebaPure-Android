package dev.infinityf4p.tiebapure.core.media

import androidx.compose.runtime.Composable
import dev.infinityf4p.tiebapure.core.designsystem.ThreadMediaPreviewAction
import dev.infinityf4p.tiebapure.core.model.ImageContent

@Composable
fun ThreadMediaPreviewDialog(
    action: ThreadMediaPreviewAction,
    onDismiss: () -> Unit,
    onDownloadImage: (ImageContent) -> Unit,
    saveImageAction: ImageSaveAction? = null,
) {
    when (action) {
        is ThreadMediaPreviewAction.Images -> ImageGallery(
            images = action.images,
            initialPage = action.initialPage,
            onDismiss = onDismiss,
            onDownload = onDownloadImage,
            saveAction = saveImageAction,
        )
        is ThreadMediaPreviewAction.Video -> VideoPlayer(
            video = action.video,
            onDismiss = onDismiss,
        )
    }
}
