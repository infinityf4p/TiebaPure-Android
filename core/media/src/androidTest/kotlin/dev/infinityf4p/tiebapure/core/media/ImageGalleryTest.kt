package dev.infinityf4p.tiebapure.core.media

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
import dev.infinityf4p.tiebapure.core.model.ImageContent
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageGalleryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsPageSourceAndUnavailableOriginalWithoutNetwork() {
        composeRule.setContent {
            TiebaPureTheme(darkTheme = true) {
                ImageGallery(
                    images = listOf(image(null, null)),
                    initialPage = 9,
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("image-gallery").assertIsDisplayed()
        composeRule.onNodeWithTag("image-gallery-source").assertIsDisplayed()
        composeRule.onNodeWithText("预览 · 320 × 640").assertIsDisplayed()
        composeRule.onNodeWithText("无原图").assertIsDisplayed()
        composeRule.onNodeWithTag("image-gallery-retry").assertIsDisplayed()
    }

    @Test
    fun saveReportsRealProgressThenSuccess() {
        val finish = CompletableDeferred<Unit>()
        composeRule.setContent {
            TiebaPureTheme(darkTheme = true) {
                ImageGallery(
                    images = listOf(image("https://tb2.bdstatic.com/preview.jpg", null)),
                    initialPage = 0,
                    onDismiss = {},
                    saveAction = { _, progress ->
                        progress(0.42f)
                        finish.await()
                        progress(1f)
                    },
                )
            }
        }

        composeRule.onNodeWithTag("image-gallery-save").assertIsEnabled().performClick()
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithText("42", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { finish.complete(Unit) }
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("image-gallery-save-success").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("图片已保存到相册").assertIsDisplayed()
    }

    @Test
    fun saveFailureCanRetryAndOnlyThenReportsSuccess() {
        var attempts = 0
        composeRule.setContent {
            TiebaPureTheme(darkTheme = true) {
                ImageGallery(
                    images = listOf(image("https://tb2.bdstatic.com/preview.jpg", null)),
                    initialPage = 0,
                    onDismiss = {},
                    saveAction = { _, _ ->
                        attempts += 1
                        if (attempts == 1) throw IOException("fixture failure")
                    },
                )
            }
        }

        composeRule.onNodeWithTag("image-gallery-save").performClick()
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("image-gallery-save-failure").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("图片保存失败，请检查网络或存储权限后重试").assertIsDisplayed()
        composeRule.onNodeWithTag("image-gallery-save").performClick()
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("image-gallery-save-success").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun singleTapAndCloseButtonDismissWhilePagerStillPagesAtBaseScale() {
        var dismissCount = 0
        composeRule.setContent {
            TiebaPureTheme(darkTheme = true) {
                ImageGallery(
                    images = listOf(image(null, null), image(null, null)),
                    initialPage = 0,
                    onDismiss = { dismissCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("image-gallery-page-indicator").assertIsDisplayed()
        composeRule.onNodeWithTag("image-gallery-pager").performTouchInput { swipeLeft() }
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("image-gallery-image").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        )
        composeRule.waitUntil(3_000) { dismissCount == 1 }
    }

    @Test
    fun visibleCloseButtonDismissesOnce() {
        var dismissCount = 0
        composeRule.setContent {
            TiebaPureTheme(darkTheme = true) {
                ImageGallery(
                    images = listOf(image(null, null)),
                    initialPage = 0,
                    onDismiss = { dismissCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("image-gallery-close").performTouchInput { click(center) }
        composeRule.waitUntil(3_000) { dismissCount == 1 }
    }

    private fun image(thumbnail: String?, original: String?) = ImageContent(
        thumbnailUrl = thumbnail,
        originalUrl = original,
        width = 320,
        height = 640,
        showOriginalButton = false,
    )
}
