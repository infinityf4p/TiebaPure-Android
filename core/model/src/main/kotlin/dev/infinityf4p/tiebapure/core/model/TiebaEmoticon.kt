package dev.infinityf4p.tiebapure.core.model

data class TiebaEmoticonEntry(
    val imageName: String,
    val name: String,
) {
    val token: String get() = "#($name)"
    val imageUrl: String get() = TiebaEmoticon.imageUrlForImageName(imageName)
}

object TiebaEmoticon {
    const val host = "tb2.bdstatic.com"
    const val maximumNumericId = 999

    private val namesByImageName = linkedMapOf(
        "image_emoticon1" to "呵呵",
        "image_emoticon2" to "哈哈",
        "image_emoticon3" to "吐舌",
        "image_emoticon4" to "啊",
        "image_emoticon5" to "酷",
        "image_emoticon6" to "怒",
        "image_emoticon7" to "开心",
        "image_emoticon8" to "汗",
        "image_emoticon9" to "泪",
        "image_emoticon10" to "黑线",
        "image_emoticon11" to "鄙视",
        "image_emoticon12" to "不高兴",
        "image_emoticon13" to "真棒",
        "image_emoticon14" to "钱",
        "image_emoticon15" to "疑问",
        "image_emoticon16" to "阴险",
        "image_emoticon17" to "吐",
        "image_emoticon18" to "咦",
        "image_emoticon19" to "委屈",
        "image_emoticon20" to "花心",
        "image_emoticon21" to "呼~",
        "image_emoticon22" to "笑眼",
        "image_emoticon23" to "冷",
        "image_emoticon24" to "太开心",
        "image_emoticon25" to "滑稽",
        "image_emoticon26" to "勉强",
        "image_emoticon27" to "狂汗",
        "image_emoticon28" to "乖",
        "image_emoticon29" to "睡觉",
        "image_emoticon30" to "惊哭",
        "image_emoticon31" to "生气",
        "image_emoticon32" to "惊讶",
        "image_emoticon33" to "喷",
        "image_emoticon34" to "爱心",
        "image_emoticon35" to "心碎",
        "image_emoticon36" to "玫瑰",
        "image_emoticon37" to "礼物",
        "image_emoticon38" to "彩虹",
        "image_emoticon39" to "星星月亮",
        "image_emoticon40" to "太阳",
        "image_emoticon41" to "钱币",
        "image_emoticon42" to "灯泡",
        "image_emoticon43" to "茶杯",
        "image_emoticon44" to "蛋糕",
        "image_emoticon45" to "音乐",
        "image_emoticon46" to "haha",
        "image_emoticon47" to "胜利",
        "image_emoticon48" to "大拇指",
        "image_emoticon49" to "弱",
        "image_emoticon50" to "OK",
        "image_emoticon77" to "沙发",
        "image_emoticon78" to "手纸",
        "image_emoticon79" to "香蕉",
        "image_emoticon80" to "便便",
        "image_emoticon81" to "药丸",
        "image_emoticon82" to "红领巾",
        "image_emoticon83" to "蜡烛",
        "image_emoticon84" to "三道杠",
        "image_emoticon89" to "噗",
    )

    private val imageNamesByName: Map<String, String> = buildMap {
        namesByImageName.forEach { (imageName, name) ->
            put(name, imageName)
            put("小$name", imageName)
        }
        put("呵", "image_emoticon1")
        put("笑", "image_emoticon2")
        put("大笑", "image_emoticon2")
        put("高兴", "image_emoticon7")
        put("笑脸", "image_emoticon7")
        put("黑头", "image_emoticon10")
        put("黑脸", "image_emoticon10")
        put("黑头高兴", "image_emoticon7")
        put("黑头开心", "image_emoticon7")
        put("黑头笑", "image_emoticon2")
    }

    val catalog: List<TiebaEmoticonEntry> = namesByImageName.map { (imageName, name) ->
        TiebaEmoticonEntry(imageName, name)
    }

    fun normalizedName(code: String): String {
        val value = code.trim()
        return when {
            value.startsWith("#(") && value.endsWith(")") -> value.substring(2, value.length - 1)
            value.startsWith("(#") && value.endsWith(")") -> value.substring(2, value.length - 1)
            value.startsWith("[") && value.endsWith("]") -> value.substring(1, value.length - 1)
            else -> value
        }.trim()
    }

    fun imageNameFor(code: String): String? {
        val normalized = normalizedName(code)
        return when {
            normalized in namesByImageName -> normalized
            normalized == "image_emoticon" -> "image_emoticon1"
            isValidImageName(normalized) -> normalized
            else -> imageNamesByName[normalized]
        }
    }

    fun displayName(code: String): String {
        val normalized = normalizedName(code)
        val imageName = imageNameFor(normalized)
        return imageName?.let(namesByImageName::get) ?: normalized
    }

    fun displayText(code: String): String = "[${displayName(code)}]"

    fun canonicalToken(code: String): String? {
        val imageName = imageNameFor(code) ?: return null
        val canonicalName = namesByImageName[imageName] ?: imageName
        return "#($canonicalName)"
    }

    fun imageUrlFor(code: String): String? = imageNameFor(code)?.let(::imageUrlForImageName)

    internal fun imageUrlForImageName(imageName: String): String {
        require(isValidImageName(imageName)) { "Invalid Tieba emoticon image name" }
        return "https://$host/tb/editor/images/client/$imageName.png"
    }

    fun isValidImageName(value: String): Boolean {
        val prefix = "image_emoticon"
        if (!value.startsWith(prefix)) return false
        val suffix = value.substring(prefix.length)
        if (suffix.isEmpty() || suffix.length > maximumNumericId.toString().length) return false
        if (suffix.first() == '0' || suffix.any { it !in '0'..'9' }) return false
        val numericId = suffix.toIntOrNull() ?: return false
        return numericId in 1..maximumNumericId
    }
}
