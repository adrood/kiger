package kiger.canon

import kiger.temp.Temp
import kiger.tree.TreeExp
import kiger.tree.TreeStm
import kiger.utils.cons
import kiger.utils.splitFirst
import kiger.utils.tail

/**
 * From an arbitrary Tree statement, produce a list of cleaned trees satisfying the following properties:
 *
 * 1. No SEQ's or ESEQ's
 * 2. The parent of every CALL is an EXP(..) or a MOVE(TEMP t,..)
 **/
fun TreeStm.linearize(): List<TreeStm> =
    Linearizer.doStm(this).linearizeTopLevel().toList()

private object Linearizer {
    val nop = TreeStm.Exp(TreeExp.Const(0))

    fun reorder(exps: List<TreeExp>): Pair<TreeStm, List<TreeExp>> = when {
        exps.isEmpty() ->
            Pair(nop, emptyList())

        exps[0] is TreeExp.Call -> {
            val t = Temp()
            val rest = exps.tail()
            reorder(cons(TreeExp.ESeq(TreeStm.Move(TreeExp.Temporary(t), exps[0]), TreeExp.Temporary(t)), rest))
        }

        else -> {
            val (head, tail) = exps.splitFirst()
            val (stms, e) = doExp(head)
            val (stms2, el) = reorder(tail)

            if (stms2.commute(e)) {
                Pair(stms % stms2, cons(e, el))
            } else {
                val t = Temp()
                Pair(stms % TreeStm.Move(TreeExp.Temporary(t), e) % stms2, cons(TreeExp.Temporary(t), el))
            }
        }
    }

    fun reorderExp(el: List<TreeExp>, build: (List<TreeExp>) -> TreeExp): Pair<TreeStm, TreeExp> {
        val (stms, el2) = reorder(el)
        return Pair(stms, build(el2))
    }

    fun reorderStm(el: List<TreeExp>, build: (List<TreeExp>) -> TreeStm): TreeStm {
        val (stms, el2) = reorder(el)
        return stms % build(el2)
    }

    fun doStm(stm: TreeStm): TreeStm = when (stm) {
        is TreeStm.Seq     -> doStm(stm.lhs) % doStm(stm.rhs)
        is TreeStm.Jump    -> reorderStm(listOf(stm.exp)) { TreeStm.Jump(it.single(), stm.labels) }
        is TreeStm.CJump   -> reorderStm(listOf(stm.lhs, stm.rhs)) { val (l, r) = it; TreeStm.CJump(stm.relop, l, r, stm.trueLabel, stm.falseLabel) }
        is TreeStm.Move    -> when (stm.target) {
            is TreeExp.Temporary ->
                if (stm.source is TreeExp.Call)
                    reorderStm(cons(stm.source.func, stm.source.args)) { val (h, t) = it.splitFirst(); TreeStm.Move(TreeExp.Temporary(stm.target.temp), TreeExp.Call(h, t)) }
                else
                    reorderStm(listOf(stm.source)) { TreeStm.Move(TreeExp.Temporary(stm.target.temp), it.single()) }
            is TreeExp.Mem -> reorderStm(listOf(stm.target.exp, stm.source)) { val (e, b) = it; TreeStm.Move(TreeExp.Mem(e), b) }
            is TreeExp.ESeq -> doStm(TreeStm.Seq(stm.target.stm, TreeStm.Move(stm.target.exp, stm.source)))
            else -> error("invalid target: ${stm.target}")
        }
        is TreeStm.Exp     ->
            if (stm.exp is TreeExp.Call)
                reorderStm(cons(stm.exp.func, stm.exp.args)) { val (h, t) = it.splitFirst(); TreeStm.Exp(TreeExp.Call(h, t)) }
            else
                reorderStm(listOf(stm.exp)) { TreeStm.Exp(it.single()) }
        is TreeStm.Labeled -> reorderStm(emptyList()) { stm }
    }

    fun doExp(exp: TreeExp): Pair<TreeStm, TreeExp> = when (exp) {
        is TreeExp.BinOp    -> reorderExp(listOf(exp.lhs, exp.rhs)) { val (a, b) = it; TreeExp.BinOp(exp.binop, a, b) }
        is TreeExp.Mem      -> reorderExp(listOf(exp.exp)) { TreeExp.Mem(it.single()) }
        is TreeExp.ESeq     -> {
            val stms = doStm(exp.stm)
            val (stms2, e) = doExp(exp.exp)
            Pair(stms % stms2, e)
        }
        is TreeExp.Call     -> reorderExp(cons(exp.func, exp.args)) { val (h, t) = it.splitFirst(); TreeExp.Call(h, t) }
        else                -> reorderExp(emptyList()) { exp }
    }
}

/**
 * Merge two statements into sequence, removing constant expressions.
 */
private operator fun TreeStm.mod(rhs: TreeStm): TreeStm = when {
    this is TreeStm.Exp && exp is TreeExp.Const -> rhs
    rhs is TreeStm.Exp && rhs.exp is TreeExp.Const -> this
    else -> TreeStm.Seq(this, rhs)
}

/**
 * Calculates conservative approximation to whether expressions commute.
 */
private fun TreeStm.commute(rhs: TreeExp): Boolean = when {
    this is TreeStm.Exp && exp is TreeExp.Const -> true
    rhs is TreeExp.Name                         -> true
    rhs is TreeExp.Const                        -> true
    else                                        -> false
}

/**
 * Get rid of the top-level SEQ's
 */
private fun TreeStm.linearizeTopLevel(): Sequence<TreeStm> =
    if (this is TreeStm.Seq) {
        lhs.linearizeTopLevel() + rhs.linearizeTopLevel()
    } else {
        sequenceOf(this)
    }


