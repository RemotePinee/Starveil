package com.starveil.tools.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * 把 starveil 自定义规则注册给 detekt。
 *
 * ruleset id 是 `starveil`，对应 `config/detekt/detekt.yml` 里的顶层 `starveil:` 段；
 * 该段的键必须与各 Rule 的 `Issue.id`（即类名）一致，否则 `config.validation` 会报未知规则。
 */
class StarveilRuleSetProvider : RuleSetProvider {

    override val ruleSetId: String = RULE_SET_ID

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            NoHardcodedColor(config),
            NoBareDp(config),
        ),
    )

    private companion object {
        const val RULE_SET_ID = "starveil"
    }
}
