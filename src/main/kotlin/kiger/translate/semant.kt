package kiger.translate

import kiger.absyn.*
import kiger.diag.Diagnostics
import kiger.env.EnvEntry
import kiger.env.SymbolTable
import kiger.frame.Fragment
import kiger.lexer.SourceLocation
import kiger.lexer.Token
import kiger.lexer.Token.Operator
import kiger.lexer.Token.Symbol
import kiger.temp.Label
import kiger.types.Type

private enum class Kind {
    ARITH, COMP, EQ
}

private fun Token.Operator.classify(): Kind = when (this) {
    Operator.Plus -> Kind.ARITH
    Operator.Minus -> Kind.ARITH
    Operator.Multiply-> Kind.ARITH
    Operator.Divide -> Kind.ARITH
    Operator.LessThan -> Kind.COMP
    Operator.GreaterThan -> Kind.COMP
    Operator.LessThanOrEqual -> Kind.COMP
    Operator.GreaterThanOrEqual -> Kind.COMP
    Operator.EqualEqual -> Kind.EQ
    Operator.NotEqual -> Kind.EQ
}

data class TranslationResult(val exp: TrExp, val type: Type)

data class DeclTranslationResult(val venv: SymbolTable<EnvEntry>, val tenv: SymbolTable<Type>, val exps: List<TrExp>) {
    constructor(venv: SymbolTable<EnvEntry>, tenv: SymbolTable<Type>, vararg exps: TrExp): this(venv, tenv, exps.asList())
}

class Translator {

    private val diagnostics = Diagnostics()

    private val translate = Translate()
    private val errorResult = TranslationResult(translate.errorExp, Type.Nil)

    var baseVenv = SymbolTable<EnvEntry>()
    var baseTenv = run {
        var env = SymbolTable<Type>()
        env = env.enter(Symbol("int"), Type.Int)
        env = env.enter(Symbol("string"), Type.String)
        env
    }

    companion object {
        fun transProg(ex: Expression): List<Fragment> {
            val translator = Translator()
            val translate = translator.translate
            val mainLevel = translate.newLevel(translate.outermost, Label("main"), emptyList())

            val exp = translator.transExp(ex, translator.baseVenv, translator.baseTenv, mainLevel, null).exp

            translate.procEntryExit(mainLevel, exp)
            return translate.fragments
        }
    }

    fun transExp(e: Expression, venv: SymbolTable<EnvEntry>, tenv: SymbolTable<Type>, level: Level, breakLabel: Label?): TranslationResult {
        fun trexp(exp: Expression): TranslationResult = when (exp) {
            is Expression.Nil ->
                TranslationResult(translate.nilExp, Type.Nil)

            is Expression.Int ->
                TranslationResult(translate.intLiteral(exp.value), Type.Int)

            is Expression.String ->
                TranslationResult(translate.stringLiteral(exp.value), Type.String)

            is Expression.Op -> {
                val (le, lt) = trexp(exp.left)
                val (re, rt) = trexp(exp.right)

                fun checkEquality() = when (lt) {
                    Type.Int,
                    Type.String,
                    is Type.Array,
                    is Type.Record -> checkType(lt, rt, exp.pos)
                    else -> {
                        diagnostics.error("can only check equality on int, string, array of record types, found $rt", exp.pos)
                    }
                }

                fun checkComparable() = when (lt) {
                    Type.Int,
                    Type.String -> checkType(lt, rt, exp.pos)
                    else -> {
                        diagnostics.error("can only compare int or string for ordering, found $rt", exp.pos)
                    }
                }

                when (exp.op.classify()) {
                    Kind.ARITH -> {
                        checkType(Type.Int, lt, exp.pos)
                        checkType(Type.Int, rt, exp.pos)
                        TranslationResult(translate.binop(exp.op, le, re), Type.Int)
                    }
                    Kind.COMP -> {
                        checkComparable()
                        TranslationResult(translate.relop(exp.op, le, re), Type.Int)
                    }
                    Kind.EQ -> {
                        checkEquality()
                        TranslationResult(translate.relop(exp.op, le, re), Type.Int)
                    }
                }
            }

            is Expression.Var ->
                transVar(exp.variable, tenv, venv, level, breakLabel)

            is Expression.Record -> {
                val t = tenv[exp.typ]
                if (t == null) {
                    diagnostics.error("record type ${exp.typ} not found", exp.pos)
                    errorResult
                } else {
                    val ty = t.actualType(exp.pos)
                    if (ty is Type.Record) {
                        val exps = exp.fields.map { trexp(it.exp) }
                        val locations = exp.fields.map { it.pos }

                        val fts = exps.map { it.type }.zip(locations)
                        val fes = exps.map { it.exp }

                        checkRecord(ty.fields, fts, exp.pos)

                        TranslationResult(translate.record(fes), ty)

                    } else {
                        typeMismatch("record", ty, exp.pos)
                    }
                }
            }

            is Expression.Seq -> {
                val exps = exp.exps.map { trexp(it.first) }
                val type = if (exps.isEmpty()) Type.Unit else exps.last().type

                TranslationResult(translate.sequence(exps.map { it.exp }), type)
            }

            is Expression.Assign -> {
                val (vexp, vty) = transVar(exp.variable, tenv, venv, level, breakLabel)
                val (eexp, ety) = trexp(exp.exp)

                checkType(vty, ety, exp.pos)

                TranslationResult(translate.assign(vexp, eexp), Type.Unit)
            }

            is Expression.If -> {
                val (thenExp, thenTy) = trexp(exp.then)
                val (testExp, testTy) = trexp(exp.test)

                checkType(Type.Int, testTy, exp.pos)

                val elseExp = if (exp.alt != null) {
                    val (elseExp, elseTy) = trexp(exp.alt)
                    checkType(thenTy, elseTy, exp.pos)
                    elseExp

                } else {
                    checkType(Type.Unit, thenTy, exp.pos)
                    null
                }

                TranslationResult(translate.ifElse(testExp, thenExp, elseExp), thenTy)
            }

            is Expression.While -> {
                val doneLabel = Label()
                val (testExp, testTy) = trexp(exp.test)
                val (bodyExp, bodyTy) = transExp(exp.body, venv, tenv, level, doneLabel)

                checkType(Type.Int, testTy, exp.pos);
                checkType(Type.Unit, bodyTy, exp.pos);

                TranslationResult(translate.loop(testExp, bodyExp, doneLabel), Type.Unit)
            }

            is Expression.Break -> {
                if (breakLabel != null) {
                    TranslationResult(translate.doBreak(breakLabel), Type.Unit)
                } else {
                    diagnostics.error("invalid break outside loop", exp.pos)
                    errorResult
                }
            }

            is Expression.Let -> {
                var venv2 = venv
                var tenv2 = tenv
                val dexps = mutableListOf<TrExp>()

                for (dec in exp.declarations) {
                    val (venv1, tenv1, exps1) = transDec(dec, venv2, tenv2, level, breakLabel)
                    venv2 = venv1
                    tenv2 = tenv1
                    dexps += exps1
                }

                val (bodyExp, bodyTy) = transExp(exp.body, venv2, tenv2, level, breakLabel)
                TranslationResult(translate.letExp(dexps, bodyExp), bodyTy)
            }

            is Expression.Array -> {
                val t = tenv[exp.typ]
                if (t == null) {
                    diagnostics.error("type ${exp.typ} not found", exp.pos)
                    errorResult
                } else {
                    val at = t.actualType(exp.pos)
                    if (at is Type.Array) {
                        val (sizeExp, sizeTy) = trexp(exp.size)
                        val (initExp, initTy) = trexp(exp.init)
                        checkType(Type.Int, sizeTy, exp.pos)
                        checkType(at.elementType, initTy, exp.pos)
                        TranslationResult(translate.array(sizeExp, initExp), at)

                    } else {
                        typeMismatch("array", at, exp.pos)
                    }
                }
            }

            is Expression.For -> {
                // rewrite for to while and translate the while

                val limit = Symbol("limit") // TODO: fresh symbol?
                val ivar = Variable.Simple(exp.variable, exp.pos)
                val limitVar = Variable.Simple(limit, exp.pos)
                val letDecs = listOf(
                        Declaration.Var(exp.variable, exp.escape, null, exp.lo, exp.pos),
                        Declaration.Var(limit, false, null, exp.hi, exp.pos))

                val loop = Expression.While(
                        test = Expression.Op(Expression.Var(ivar), Operator.LessThanOrEqual, Expression.Var(limitVar), exp.pos),
                        body = Expression.Seq(listOf(
                                exp.body to exp.pos,
                                Expression.Assign(ivar, Expression.Op(Expression.Var(ivar), Operator.Plus, Expression.Int(1), exp.pos), exp.pos) to exp.pos)),
                        pos = exp.pos
                )

                trexp(Expression.Let(letDecs, loop, exp.pos))
            }

            is Expression.Call -> {
                val func = venv[exp.func]
                when (func) {
                    null -> {
                        diagnostics.error("function ${exp.func} is not defined", exp.pos)
                        errorResult
                    }
                    is EnvEntry.Var -> {
                        diagnostics.error("function expected, but variable of type ${func.type} found", exp.pos)
                        errorResult
                    }
                    is EnvEntry.Function -> {
                        val argExps = exp.args.map { trexp(it) }
                        checkFormals(func.formals, argExps, exp.pos)

                        TranslationResult(translate.call(level, func.level, func.label, argExps.map { it.exp }, func.result == Type.Unit), func.result)
                    }
                }
            }
        }


        return trexp(e)
    }

    private fun transVar(v: Variable, tenv: SymbolTable<Type>, venv: SymbolTable<EnvEntry>, level: Level, breakLabel: Label?): TranslationResult {
        return when (v) {
            is Variable.Simple -> {
                val entry = venv[v.name]
                when (entry) {
                    is EnvEntry.Var ->
                        TranslationResult(translate.simpleVar(entry.access, level), entry.type.actualType(v.pos))
                    is EnvEntry.Function -> {
                        diagnostics.error("expected variable, but function found", v.pos)
                        errorResult
                    }
                    null -> {
                        diagnostics.error("undefined variable: ${v.name}", v.pos)
                        errorResult
                    }
                }
            }
            is Variable.Field -> {
                val (exp, ty) = transVar(v.variable, tenv, venv, level, breakLabel)
                when (ty) {
                    is Type.Record -> {
                        val index = ty.fields.indexOfFirst { v.name == it.first }
                        if (index != -1) {
                            val fieldType = ty.fields[index].second.actualType(v.pos)
                            TranslationResult(translate.fieldVar(exp, index), fieldType)

                        } else {
                            diagnostics.error("could not find field ${v.name} for $ty", v.pos)
                            errorResult
                        }
                    }
                    else -> {
                        diagnostics.error("expected record type, but $ty found", v.pos)
                        errorResult
                    }
                }
            }
            is Variable.Subscript -> {
                val (exp, ty) = transVar(v.variable, tenv, venv, level, breakLabel)
                val actualType = ty.actualType(v.pos)

                if (actualType is Type.Array) {
                    val (exp1, ty1) = transExp(v.exp, venv, tenv, level, breakLabel)
                    if (ty1 == Type.Int) {
                        TranslationResult(translate.subscriptVar(exp, exp1), actualType.elementType)

                    } else {
                        diagnostics.error("array subscript should be int, but was $ty1}", v.pos)
                        errorResult
                    }

                } else {
                    typeMismatch("array", actualType, v.pos)
                }
            }
        }
    }

    private fun transDec(dec: Declaration, venv: SymbolTable<EnvEntry>, tenv: SymbolTable<Type>, level: Level, breakLabel: Label?): DeclTranslationResult =
        when (dec) {
            is Declaration.Functions -> transDec(dec, venv, tenv, level, breakLabel)
            is Declaration.Var       -> transDec(dec, venv, tenv, level, breakLabel)
            is Declaration.Types     -> transDec(dec, venv, tenv, level, breakLabel)
        }

    private fun transDec(dec: Declaration.Var, venv: SymbolTable<EnvEntry>, tenv: SymbolTable<Type>, level: Level, breakLabel: Label?): DeclTranslationResult {
        val (exp, ty) = transExp(dec.init, venv, tenv, level, breakLabel)
        val type = if (dec.type == null) {
            if (ty == Type.Nil)
                diagnostics.error("can't use nil", dec.pos)

            ty
        } else {
            val type = tenv[dec.type.first]
            if (type == null) {
                diagnostics.error("type ${dec.type.first} not found", dec.type.second)
                ty
            } else {
                val at = type.actualType(dec.type.second)
                checkType(at, ty, dec.pos)
                at
            }
        }

        val acc = translate.allocLocal(level, !dec.escape)
        val varExp = translate.simpleVar(acc, level)
        val venv2 = venv.enter(dec.name, EnvEntry.Var(acc, type))

        return DeclTranslationResult(venv2, tenv, translate.assign(varExp, exp))
    }

    private fun transDec(dec: Declaration.Types, venv: SymbolTable<EnvEntry>, tenv: SymbolTable<Type>, level: Level, breakLabel: Label?): DeclTranslationResult {
        // Type declarations may be recursive (or mutually recursive). Therefore we'll perform the translation
        // in two steps: first we'll fill tenv with empty headers, then translate the types.

        val tenv2 = dec.declarations.fold(tenv) { env, d -> env.enter(d.name, Type.Name(d.name)) }

        for (d in dec.declarations) {
            val type = tenv2[d.name] as Type.Name
            type.type = transTy(d.type, tenv2)
        }

        // Now that all the types have been initialized, check for cycles
        for (d in dec.declarations) {
            checkCycle(tenv2[d.name] as Type.Name, d.pos)
        }

        checkDuplicates(dec.declarations.map { Pair(it.name, it.pos) })

        return DeclTranslationResult(venv, tenv2)
    }

    private fun transDec(dec: Declaration.Functions, venv: SymbolTable<EnvEntry>, tenv: SymbolTable<Type>, level: Level, breakLabel: Label?): DeclTranslationResult {
        // First pass: check formal types and store header info into venv
        fun transFun(f: FunctionDeclaration, env: SymbolTable<EnvEntry>): SymbolTable<EnvEntry> {
            val returnType = f.result?.let { tenv.lookupType(it.first, it.second) } ?: Type.Unit
            val formals = f.params.map { Pair(it.name, tenv.lookupType(it.typ, it.pos)) }
            val escapes = f.params.map { it.escape }
            val label = Label(f.name.name)

            checkDuplicates(f.params.map { Pair(it.name, it.pos) })

            return env.enter(f.name, EnvEntry.Function(translate.newLevel(level, label, escapes), label, formals, returnType))
        }

        val venv2 = dec.declarations.fold(venv) { env, d -> transFun(d, env) }

        // second pass: do type checking, put VarEntry on venv and check body
        fun transBody(f: FunctionDeclaration) {
            val func = venv2[f.name] as EnvEntry.Function
            val newLevel = func.level as Level.Lev

            fun transParam(param: Field, access: Access): Triple<Symbol, Type, Access> =
                Triple(param.name, tenv.lookupType(param.typ, param.pos), access)

            val params2 = f.params.zip(translate.formals(newLevel)).map { transParam(it.first, it.second) }

            val venv3 = params2.fold(venv2) { env, p ->
                venv2.enter(p.first, EnvEntry.Var(p.third, p.second))
            }

            val (exp, ty) = transExp(f.body, venv3, tenv, newLevel, null)

            checkType(func.result, ty, f.pos)

            translate.procEntryExit(newLevel, exp)
        }

        checkDuplicates(dec.declarations.map { Pair(it.name, it.pos) })

        for (d in dec.declarations)
            transBody(d)

        return DeclTranslationResult(venv2, tenv)
    }

    private fun SymbolTable<Type>.lookupType(name: Symbol, pos: SourceLocation): Type =
        this[name] ?: run {
            diagnostics.error("could not find type $name", pos)
            Type.Unit
        }

    /**
     * Translate type-references in source code to Types.
     */
    private fun transTy(type: TypeRef, tenv: SymbolTable<Type>): Type = when (type) {
        is TypeRef.Name   -> tenv.lookupType(type.name, type.pos)
        is TypeRef.Record -> {
            checkDuplicates(type.fields.map { Pair(it.name, it.pos) })
            Type.Record(type.fields.map { Pair(it.name, tenv.lookupType(it.typ, it.pos)) })
        }
        is TypeRef.Array  -> Type.Array(tenv.lookupType(type.elementType, type.pos))
    }

    // Check that mutually recursive types include an array or record
    private fun checkCycle(from: Type.Name, pos: SourceLocation) {
        val seen = mutableSetOf<Symbol>()
        var type: Type? = from

        while (type is Type.Name) {
            if (!seen.add(type.name)) {
                diagnostics.error("type ${from.name} is involved in cyclic definition", pos)
                break
            }

            type = type.type
        }
    }

    private fun typeMismatch(expected: String, actual: Type, pos: SourceLocation): TranslationResult {
        diagnostics.error("expected $expected, but got $actual", pos)
        return errorResult
    }

    private fun checkDuplicates(items: List<Pair<Symbol, SourceLocation>>) {
        checkDuplicates(items.map { it.first }, items.map { it.second })
    }

    private fun checkDuplicates(names: List<Symbol>, positions: List<SourceLocation>) {
        require(names.size == positions.size)

        names.forEachIndexed { i, name ->
            if (name in names.subList(i + 1, names.size))
                diagnostics.error("duplicated definition: $name", positions[i])
        }
    }

    private fun checkType(expected: Type, type: Type, pos: SourceLocation) {
        if (type != expected)
            typeMismatch(expected.toString(), type, pos)
    }

    private fun checkRecord(ts: List<Pair<Symbol, Type>>, fs: List<Pair<Type, SourceLocation>>, pos: SourceLocation) {
        if (ts.size != fs.size) {
            diagnostics.error("${ts.size} fields needed, but got ${fs.size}", pos)
        } else {
            for ((t1, t2) in ts.zip(fs))
                checkType(t1.second, t2.first, t2.second)
        }
    }

    private fun checkFormals(ts: List<Pair<Symbol, Type>>, es: List<TranslationResult>, pos: SourceLocation) {
        if (es.size != ts.size) {
            diagnostics.error("${ts.size} args needed, but got ${es.size}", pos)
        } else {
            for ((t, e) in ts.zip(es)) {
                checkType(t.second, e.type, pos)
            }
        }
    }

    private fun Type.actualType(pos: SourceLocation) = actualType(pos, diagnostics)
}

