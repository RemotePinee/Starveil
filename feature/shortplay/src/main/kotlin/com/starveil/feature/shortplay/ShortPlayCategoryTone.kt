package com.starveil.feature.shortplay

/**
 * 分类条的文字配色档位：同一套手感用在两种底色上。
 *
 * 压画面与压页面不能共用一套颜色——画面底色由内容决定（固定白字），页面底色跟随
 * 浅色/深色主题（必须用 `colorScheme`），混用会让其中一边彻底看不见。
 */
internal enum class ShortPlayCategoryTone {
    /** 压在封面 / 视频画面上：固定白字，不跟明暗主题。剧场页顶部那一条用它。 */
    OnImage,

    /** 压在页面底色上（分类页）：跟随 `colorScheme`，浅色主题下是深色字。 */
    OnSurface,
}
