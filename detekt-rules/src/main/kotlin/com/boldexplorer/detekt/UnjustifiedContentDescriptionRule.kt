package com.boldexplorer.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Flags `contentDescription = ...` assignments that don't carry an inline `// a11y: <reason>`
 * justification comment anywhere within the nearest enclosing composable call (the first
 * PascalCase-named call found while walking up from the assignment — e.g. `TextButton`, `Row`,
 * `Switch` — as opposed to lowercase `Modifier` extension calls like `semantics`/`padding`).
 *
 * This is a PSI text-matching heuristic, not semantic analysis: it can't tell whether a
 * `contentDescription` is actually redundant with visible text, only whether the author left a
 * reason. See "Accessibility constraints" in AGENTS.md for the decision test authors should apply
 * before adding one.
 */
class UnjustifiedContentDescriptionRule(config: Config) : Rule(config) {
    override val issue =
        Issue(
            "UnjustifiedContentDescription",
            Severity.Warning,
            "contentDescription set without an `// a11y: <reason>` justification comment nearby " +
                "- see \"Accessibility constraints\" in AGENTS.md.",
            Debt.FIVE_MINS,
        )

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)

        if (expression.operationToken != KtTokens.EQ) return
        val left = expression.left as? KtNameReferenceExpression ?: return
        if (left.getReferencedName() != "contentDescription") return

        val enclosingComposableCall =
            expression.parents
                .filterIsInstance<KtCallExpression>()
                .firstOrNull { call ->
                    val calleeName = call.calleeExpression?.text
                    calleeName != null && calleeName.firstOrNull()?.isUpperCase() == true
                }

        val searchText = enclosingComposableCall?.text ?: expression.parent?.text
        if (searchText == null || !A11Y_COMMENT_REGEX.containsMatchIn(searchText)) {
            report(CodeSmell(issue, Entity.from(expression), issue.description))
        }
    }

    companion object {
        private val A11Y_COMMENT_REGEX = Regex("""//\s*a11y:""", RegexOption.IGNORE_CASE)
    }
}
