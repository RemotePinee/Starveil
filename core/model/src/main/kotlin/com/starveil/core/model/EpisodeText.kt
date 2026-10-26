package com.starveil.core.model

/**
 * 集号展示的两行文本。
 *
 * 播放入口只放得下一行文字，但真实数据里"集"有三种长相：
 * - 编号型：`第5集` / `第5期` / `12`
 * - 日期型：`2026-10-24`（很多综艺只给日期）
 * - 文本型：`国语原声版` / `HD中字` / 杂标题
 *
 * 前两种能塞进窄格子，第三种塞进去只会变成「2026-10-」这种残片。
 * [wide] 就是给 UI 的信号：true 时用两行卡片（primary + secondary），不要再用固定宽度的数字格。
 */
data class EpisodeText(
    val primary: String,
    val secondary: String = "",
    val wide: Boolean = false,
)

/** `第5集` / `第5期` / `第5话` / `第5回` 里的期号，带单位。 */
private val RANK_PATTERN = Regex("""第\s*(\d{1,4})\s*([集期话回])""")

/** 日期：`2026-10-24` / `2026/10/24` / `2026年10月24日` / `10-24`。 */
private val DATE_PATTERN = Regex("""(?:20\d{2}[-/年])?(\d{1,2})[-/月](\d{1,2})""")

/**
 * 紧凑日期：`20260119`。很多综艺只给这种纯数字日期串，没有分隔符，
 * [DATE_PATTERN] 匹配不上，落到文本兜底就会显示成 `…260119` 这种残片。
 * 月份与日期都限定在合法范围，避免把 4 位集号误认成日期。
 */
private val COMPACT_DATE = Regex("""^(?:20\d{2})?(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])$""")

/** 纯数字标题：少数后端把集号直接当标题。 */
private val PURE_NUMBER = Regex("""^\s*\d{1,4}\s*$""")

/** 文本型标签保留尾部多少字符：日期/版本名的区分度都在尾部。 */
private const val TEXT_TAIL = 6

/**
 * 标题 + 集号 → 两行展示文本。
 *
 * 优先级：期号 → 日期 → 纯数字 → 文本兜底。判错只会让按钮变宽，不会丢信息。
 * 用 `when` 表达式只留一个 return：detekt 的 ReturnCount 上限是 2。
 */
fun episodeText(title: String, number: Int): EpisodeText {
    val text = title.trim()
    val rank = RANK_PATTERN.find(text)
    val date = DATE_PATTERN.find(text)
    val compact = COMPACT_DATE.find(text)
    return when {
        rank != null -> EpisodeText(
            primary = "第${rank.groupValues[1]}${rank.groupValues[2]}",
            // `第5期 2026-10-24`：主行期号，副行日期
            secondary = DATE_PATTERN.find(text.replace(rank.value, "").trim())
                ?.let { "${it.groupValues[1].padStart(2, '0')}-${it.groupValues[2].padStart(2, '0')}" }
                .orEmpty(),
        )

        // 年份不进主行：同一部综艺基本同一年，省一列宽度
        date != null -> EpisodeText(
            primary = "${date.groupValues[1].padStart(2, '0')}-${date.groupValues[2].padStart(2, '0')}",
            secondary = "第${number}集",
        )

        // 紧凑日期：主行只留月-日，副行补集号
        compact != null -> EpisodeText(
            primary = "${compact.groupValues[1]}-${compact.groupValues[2]}",
            secondary = "第${number}集",
        )

        PURE_NUMBER.matches(text) -> EpisodeText(primary = text)

        else -> EpisodeText(
            primary = if (text.length > TEXT_TAIL) "…" + text.takeLast(TEXT_TAIL) else text,
            secondary = "第${number}集",
            wide = true,
        )
    }
}

/** 一批集里只要有一条是文本型，整批就走宽卡片：混排比统一难看得多。 */
fun episodeLayoutWide(episodes: List<PlayUrl>): Boolean =
    episodes.any { episodeText(it.title, it.episodeNumber).wide }

/**
 * 集数格子里显示的文案（窄数字格）。
 *
 * 保持旧行为不破坏既有调用：去掉「第」「集」与空白，去空后用后端 nid 兜底。
 */
fun episodeChipLabel(title: String, number: Int): String {
    val stripped = title.filterNot { it.isWhitespace() || it == '第' || it == '集' }
    return stripped.ifEmpty { number.toString() }
}
