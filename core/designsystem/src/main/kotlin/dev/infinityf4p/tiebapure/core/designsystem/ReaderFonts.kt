package dev.infinityf4p.tiebapure.core.designsystem

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import dev.infinityf4p.tiebapure.core.model.ReaderFontFamily
import java.util.concurrent.ConcurrentHashMap

val LocalReaderFontRevision = staticCompositionLocalOf { 0L }

object ReaderTypefaceRegistry {
    private val imported = ConcurrentHashMap<String, Typeface>()
    private val rounded = FontFamily(Typeface.create("sans-serif-rounded", Typeface.NORMAL))

    fun register(id: String, typeface: Typeface) {
        val family = ReaderFontFamily.imported(id) ?: return
        imported[family.rawValue] = typeface
    }

    fun unregister(id: String) {
        ReaderFontFamily.imported(id)?.let { imported.remove(it.rawValue) }
    }

    fun resolve(family: ReaderFontFamily): FontFamily = when (family) {
        ReaderFontFamily.System -> FontFamily.Default
        ReaderFontFamily.Serif -> FontFamily.Serif
        ReaderFontFamily.Rounded -> rounded
        ReaderFontFamily.Monospace -> FontFamily.Monospace
        else -> imported[family.rawValue]?.let(::FontFamily) ?: FontFamily.Default
    }
}

@Composable
fun readerFontFamily(family: ReaderFontFamily): FontFamily {
    LocalReaderFontRevision.current
    return ReaderTypefaceRegistry.resolve(family)
}
