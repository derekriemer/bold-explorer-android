package com.boldexplorer.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals

class UnjustifiedContentDescriptionRuleTest {
    private val rule = UnjustifiedContentDescriptionRule(Config.empty)

    @Test
    fun `flags contentDescription with no a11y comment anywhere nearby`() {
        val code =
            """
            fun sample() {
                TextButton(
                    onClick = { },
                    modifier = Modifier.semantics { contentDescription = "Stop alignment guidance" },
                ) { Text("Stop alignment") }
            }
            """.trimIndent()
        assertEquals(1, rule.compileAndLint(code).size)
    }

    @Test
    fun `does not flag contentDescription with an a11y comment inside the semantics lambda`() {
        val code =
            """
            fun sample() {
                TextButton(
                    onClick = { },
                    modifier = Modifier.semantics {
                        // a11y: visible text is just the name; this names the action too.
                        contentDescription = "Move to Foo"
                    },
                ) { Text("Foo") }
            }
            """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `does not flag contentDescription with an a11y comment before the modifier argument`() {
        val code =
            """
            fun sample() {
                TextButton(
                    onClick = { },
                    // a11y: "Import" alone doesn't say what's being added.
                    modifier = Modifier.semantics { contentDescription = "Import GPX file" },
                ) { Text("Import") }
            }
            """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `scopes the search to the nearest enclosing composable, not sibling calls`() {
        val code =
            """
            fun sample() {
                Row {
                    // a11y: this comment belongs to the row, not the button below.
                    TextButton(
                        onClick = { },
                        modifier = Modifier.semantics { contentDescription = "Delete Foo" },
                    ) { Text("Delete") }
                }
            }
            """.trimIndent()
        assertEquals(1, rule.compileAndLint(code).size)
    }
}

private typealias Config = io.gitlab.arturbosch.detekt.api.Config
