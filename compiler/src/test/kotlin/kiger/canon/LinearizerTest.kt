package kiger.canon

import kiger.absyn.Symbol
import kiger.env.EnvEntry
import kiger.parser.parseExpression
import kiger.temp.Label
import kiger.temp.resetLabelSequence
import kiger.temp.resetTempSequence
import kiger.translate.Level
import kiger.translate.SemanticAnalyzer
import kiger.tree.TreeStm
import kiger.types.Type
import org.junit.Assert.assertEquals
import org.junit.Test

class LinearizerTest {

    @Test
    fun exampleLinearization() {
        assertLinearization("f(f(1, 2), f(f(3, 4), 5))",
                """
                MOVE t1, Call[Name[f], [1, 2]]
                MOVE t4, t1
                MOVE t3, Call[Name[f], [3, 4]]
                MOVE t2, Call[Name[f], [t3, 5]]
                Call[Name[f], [t4, t2]]
                """)
    }

    private fun assertLinearization(code: String, expected: String) {
        assertEquals(expected.trimIndent(), linearize(code).joinToString("\n").trimIndent())
    }

    private fun linearize(code: String): List<TreeStm> {
        val translator = SemanticAnalyzer()
        translator.baseVenv = translator.baseVenv.enter(Symbol("f"), EnvEntry.Function(Level.Top, Label("f"), listOf(Type.Int, Type.Int), Type.Int))

        resetLabelSequence()
        resetTempSequence()
        val e = parseExpression(code)
        val exp = translator.transTopLevelExp(e).exp

        return exp.asNx().linearize()
    }
}
