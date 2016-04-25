package kiger.translate

import kiger.frame.Fragment
import kiger.frame.Frame
import kiger.lexer.Token
import kiger.temp.Label
import kiger.temp.Temp
import kiger.tree.BinaryOp
import kiger.tree.RelOp
import kiger.tree.TreeExp
import kiger.tree.TreeStm

object Translate {
    val fragments = mutableListOf<Fragment>()

    val nilExp: TrExp = TrExp.Ex(TreeExp.Const(0))

    val errorExp: TrExp = TrExp.Ex(TreeExp.Const(0))

    fun intLiteral(value: Int): TrExp = TrExp.Ex(TreeExp.Const(value))

    fun stringLiteral(s: String): TrExp {
        val t = fragments.asSequence().filterIsInstance<Fragment.Str>().find { s == it.value }

        return TrExp.Ex(TreeExp.Name(t?.label ?: run {
            val label = Label()
            fragments += Fragment.Str(label, s)
            label
        }))
    }

    fun binop(op: Token.Operator, e1: TrExp, e2: TrExp): TrExp {
        val left = e1.unEx()
        val right = e2.unEx()
        val treeOp = when (op) {
            Token.Operator.Plus     -> BinaryOp.PLUS
            Token.Operator.Minus    -> BinaryOp.MINUS
            Token.Operator.Multiply -> BinaryOp.MUL
            Token.Operator.Divide   -> BinaryOp.DIV
            else                    -> error("unexpected op: $op")
        }

        return TrExp.Ex(TreeExp.BinOp(treeOp, left, right))
    }

    fun relop(op: Token.Operator, e1: TrExp, e2: TrExp): TrExp {
        val left = e1.unEx()
        val right = e2.unEx()

        val treeOp = when (op) {
            Token.Operator.EqualEqual           -> RelOp.EQ
            Token.Operator.NotEqual             -> RelOp.NE
            Token.Operator.LessThan             -> RelOp.LT
            Token.Operator.LessThanOrEqual      -> RelOp.LE
            Token.Operator.GreaterThan          -> RelOp.GT
            Token.Operator.GreaterThanOrEqual   -> RelOp.GE
            else                                -> error("unexpected op: $op")
        }

        return TrExp.Cx { t, f -> TreeStm.CJump(treeOp, left, right, t, f) }
    }

    /**
     * fetch static links between the level of use (the
     * level passed to simpleVar) and the level of definition
     * (the level within the variable's access)
     */
    fun simpleVar(access: Access, level: Level): TrExp {
        fun iter(currentLevel: Level, acc: TreeExp): TreeExp =
            if (access.level === currentLevel) {
                Frame.exp(access.frameAccess, acc)
            } else {
                currentLevel as Level.Lev
                iter(currentLevel.parent, Frame.exp(currentLevel.frame.formals.first(), acc))
            }

        return TrExp.Ex(iter(level, TreeExp.Temporary(Frame.FP)))
    }

    fun fieldVar(base: TrExp, index: Int): TrExp =
        TrExp.Ex(memPlus(base.unEx(),
            TreeExp.BinOp(BinaryOp.MUL, TreeExp.Const(index), TreeExp.Const(Frame.wordSize))))

    fun memPlus(e1: TreeExp, e2: TreeExp): TreeExp =
        TreeExp.Mem(TreeExp.BinOp(BinaryOp.PLUS, e1, e2))

    fun subscriptVar(base: TrExp, offset: TrExp): TrExp =
        TrExp.Ex(memPlus(base.unEx(),
            TreeExp.BinOp(BinaryOp.MUL, offset.unEx(), TreeExp.Const(Frame.wordSize))))

    fun record(fields: List<TrExp>): TrExp {
        val r = Temp()
        val init = TreeStm.Move(TreeExp.Temporary(r), Frame.externalCall("allocRecord", listOf(TreeExp.Const(fields.size * Frame.wordSize))))

        val inits = fields.mapIndexed { i, e ->
            TreeStm.Move(memPlus(TreeExp.Temporary(r), TreeExp.Const(i * Frame.wordSize)), e.unEx())
        }

        return TrExp.Ex(TreeExp.ESeq(seq(listOf(init) + inits), TreeExp.Temporary(r)))
    }

    /**
     * Evaluate all expressions in sequence and return the value of the last.
     * If the last expression is a statement, then the whole sequence will be
     * a statement.
     */
    fun sequence(exps: List<TrExp>): TrExp = when (exps.size) {
        0 -> TrExp.Nx(TreeStm.Exp(TreeExp.Const(0)))
        1 -> exps.first()
        else -> {
            val first = seq(exps.subList(0, exps.size-1).map { it.unNx() })
            val last = exps.last()
            when (last) {
                is TrExp.Nx -> TrExp.Nx(TreeStm.Seq(first, last.stm))
                else        -> TrExp.Ex(TreeExp.ESeq(first, last.unEx()))
            }
        }
    }

    fun assign(left: TrExp, right: TrExp): TrExp =
        TrExp.Nx(TreeStm.Move(left.unEx(), right.unEx()))

    fun ifElse(testExp: TrExp, thenExp: TrExp, elseExp: TrExp?): TrExp {
        val r = Temp()
        val t = Label()
        val f = Label()
        val finish = Label()
        val testFun = testExp.unCx()
        return when (thenExp) {
            is TrExp.Ex -> {
                elseExp!! // if there's no else, this is Nx

                TrExp.Ex(TreeExp.ESeq(seq(
                        testFun(t, f),
                        TreeStm.Labeled(t),
                        TreeStm.Move(TreeExp.Temporary(r), thenExp.exp),
                        TreeStm.Jump(TreeExp.Name(finish), listOf(finish)),
                        TreeStm.Labeled(f),
                        TreeStm.Move(TreeExp.Temporary(r), elseExp.unEx()),
                        TreeStm.Labeled(finish)),
                    TreeExp.Temporary(r))
                )
            }
            is TrExp.Nx -> {
                if (elseExp == null) {
                    TrExp.Nx(seq(
                            testFun(t, f),
                            TreeStm.Labeled(t),
                            thenExp.stm,
                            TreeStm.Labeled(f)))
                } else {
                    TrExp.Nx(seq(
                            testFun(t, f),
                            TreeStm.Labeled(t),
                            TreeStm.Jump(TreeExp.Name(finish), listOf(finish)),
                            thenExp.stm,
                            TreeStm.Labeled(f),
                            elseExp.unNx(),
                            TreeStm.Labeled(finish)))
                }
            }
            is TrExp.Cx -> { // TODO fix this
                elseExp!! // if there's no else, this is Nx

                TrExp.Cx { tt, ff ->
                    seq(testFun(t, f),
                        TreeStm.Labeled(t),
                        thenExp.generateStatement(tt, ff),
                        TreeStm.Labeled(f),
                        elseExp.unCx()(tt, ff))
                }
            }
        }
    }

    fun loop(test: TrExp, body: TrExp, doneLabel: Label): TrExp {
        val testLabel = Label()
        val bodyLabel = Label()

        return TrExp.Nx(seq(
                        TreeStm.Labeled(testLabel),
                        TreeStm.CJump(RelOp.EQ, test.unEx(), TreeExp.Const(0), doneLabel, bodyLabel),
                        TreeStm.Labeled(bodyLabel),
                        body.unNx(),
                        TreeStm.Jump(TreeExp.Name(testLabel), listOf(testLabel)),
                        TreeStm.Labeled(doneLabel)))
    }

    fun doBreak(label: Label): TrExp =
        TrExp.Nx(TreeStm.Jump(TreeExp.Name(label), listOf(label)))

    fun letExp(decs: List<TrExp>, body: TrExp): TrExp = when (decs.size) {
        0    -> body
        1    -> TrExp.Ex(TreeExp.ESeq(decs.first().unNx(), body.unEx()))
        else -> TrExp.Ex(TreeExp.ESeq(seq(decs.map { it.unNx() }), body.unEx()))
    }

    fun array(size: TrExp, init: TrExp): TrExp =
        TrExp.Ex(Frame.externalCall("initArray", listOf(size.unEx(), init.unEx())))

    fun call(uselevel: Level, deflevel: Level, label: Label, args: List<TrExp>, isProcedure: Boolean): TrExp {
        val argExps = args.map { it.unEx() }
        val call = if (deflevel.parent == Level.Top) {
            Frame.externalCall(label.name, argExps)

        } else {
            val diff = uselevel.depth - deflevel.depth + 1
            fun iter(d: Int, curlevel: Level): TreeExp =
                if (d == 0) {
                    TreeExp.Temporary(Frame.FP)
                } else {
                    curlevel as Level.Lev
                    Frame.exp(curlevel.frame.formals.first(), iter(d - 1, curlevel.parent))
                }

            TreeExp.Call(TreeExp.Name(label), listOf(iter(diff, uselevel)) + argExps)
        }

        return if (isProcedure)
            TrExp.Nx(TreeStm.Exp(call))
        else
            TrExp.Ex(call)
    }

    fun allocLocal(level: Level, escape: Boolean): Access {
        TODO()
    }
}

fun seq(vararg statements: TreeStm): TreeStm = seq(statements.asList())

fun seq(statements: List<TreeStm>): TreeStm =
    when (statements.size) {
        0    -> error("no statements")
        1    -> statements[0]
        2    -> TreeStm.Seq(statements[0], statements[1])
        else -> TreeStm.Seq(statements[0], seq(statements.subList(1, statements.size)))
    }
