package com.boldexplorer.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class A11yRuleSetProvider : RuleSetProvider {
    override val ruleSetId = "a11y"

    override fun instance(config: Config): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                UnjustifiedContentDescriptionRule(config),
            ),
        )
}
