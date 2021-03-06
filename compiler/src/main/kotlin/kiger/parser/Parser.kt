package kiger.parser

import kiger.absyn.*
import kiger.lexer.*
import kiger.lexer.Token.Keyword.*
import kiger.lexer.Token.Keyword.Array
import kiger.lexer.Token.Keyword.Function
import kiger.lexer.Token.Operator
import kiger.lexer.Token.Operator.Equal
import kiger.lexer.Token.Punctuation.*
import kiger.lexer.Token.Sym
import java.util.*

/**
 * Parse [code] as [Expression].
 *
 * @throws SyntaxErrorException if parsing fails
 */
fun parseExpression(code: String, fileName: String = "<unknown>"): Expression =
        parseComplete(Lexer(code, fileName)) { it.parseExpression0() }

/**
 * Parses a function definition.
 */
fun parseDeclaration(code: String): Declaration =
    parseComplete(Lexer(code)) { it.parseDeclaration() }

/**
 * Executes parser on code and verifies that it consumes all input.
 *
 * @throws SyntaxErrorException if the parser fails or if it did not consume all input
 */
private fun <T> parseComplete(lexer: Lexer, callback: (Parser) -> T): T {
    val parser = Parser(lexer)
    val result = callback(parser)
    parser.expectEnd()
    return result
}

/**
 * A simple recursive descent parser.
 */
private class Parser(lexer: Lexer) {

    private val lexer = LookaheadLexer(lexer)

    fun parseDeclarations(): List<Declaration> {
        val result = ArrayList<Declaration>()

        while (lexer.hasMore && !lexer.nextTokenIs(In))
            result += parseDeclaration()

        return result
    }

    fun parseDeclaration(): Declaration = when {
        lexer.nextTokenIs(Function) -> parseFunctionDeclarations()
        lexer.nextTokenIs(Var) -> parseVarDeclaration()
        lexer.nextTokenIs(Type) -> parseTypeDeclarations()
        else -> fail(lexer.nextTokenLocation(), "expected function or var declaration")
    }

    fun parseFunctionDeclarations(): Declaration.Functions {
        val funcs = mutableListOf<FunctionDeclaration>()

        while (lexer.nextTokenIs(Function)) {
            val pos = lexer.expect(Function)
            val name = parseName().first
            val params = parseArgumentDefinitionList()
            val returnType = parseOptionalType()
            lexer.expect(Equal)
            val body = parseExpression0()

            funcs += FunctionDeclaration(name, params, returnType, body, pos)
        }

        return Declaration.Functions(funcs)
    }

    fun parseVarDeclaration(): Declaration.Var {
        val pos = lexer.expect(Var)
        val name = parseName().first
        val type = parseOptionalType()
        lexer.expect(Assign)
        val init = parseTopLevelExpression()

        return Declaration.Var(name, type, init, pos)
    }

    private fun parseTypeDeclarations(): Declaration.Types {
        val types = mutableListOf<TypeDeclaration>()

        while (lexer.nextTokenIs(Type)) {
            val pos = lexer.expect(Type)
            val name = parseName().first
            lexer.expect(Equal)
            val type = parseType()

            types += TypeDeclaration(name, type, pos)
        }

        return Declaration.Types(types)
    }

    private fun parseOptionalType() = if (lexer.readNextIf(Colon)) parseName() else null

    fun parseTopLevelExpression(): Expression {
        val location = lexer.nextTokenLocation()
        val exp = parseExpression0()

        if (lexer.nextTokenIs(Semicolon)) {
            val result = mutableListOf(exp to location)

            while (lexer.readNextIf(Semicolon)) {
                val pos = lexer.nextTokenLocation()
                result += parseExpression0() to pos
            }

            return Expression.Seq(result)
        } else {
            return exp
        }
    }


    fun parseExpression0() = when (lexer.peekToken().token) {
        is Token.Sym -> {
            val exp = parseExpression1();
            if (exp is Expression.Var && lexer.nextTokenIs(Assign))
                parseAssignTo(exp.variable)
            else
                exp
        }
        else ->
            parseExpression1()
    }

    /**
     * ```
     * expression1 ::= expression2 (("||") expression2)*
     * ```
     */
    private fun parseExpression1(): Expression {
        var exp = parseExpression2()

        while (lexer.hasMore) {
            val location = lexer.nextTokenLocation()
            when {
                lexer.readNextIf(Operator.Or) ->
                    exp = Expression.Op(exp, Operator.Or, parseExpression2(), location)
                else ->
                    return exp
            }
        }

        return exp
    }

    /**
     * ```
     * expression2 ::= expression3 (("&&") expression2)3
     * ```
     */
    private fun parseExpression2(): Expression {
        var exp = parseExpression3()

        while (lexer.hasMore) {
            val location = lexer.nextTokenLocation()
            when {
                lexer.readNextIf(Operator.And) ->
                    exp = Expression.Op(exp, Operator.And, parseExpression3(), location)
                else ->
                    return exp
            }
        }

        return exp
    }

    /**
     * ```
     * expression3 ::= expression4 (("=" | "!=") expression4)*
     * ```
     */
    fun parseExpression3(): Expression {
        var exp = parseExpression4()

        while (lexer.hasMore) {
            val location = lexer.nextTokenLocation()
            when {
                lexer.readNextIf(Equal) ->
                    exp = Expression.Op(exp, Equal, parseExpression4(), location)
                lexer.readNextIf(Operator.NotEqual) ->
                    exp = Expression.Op(exp, Operator.NotEqual, parseExpression4(), location)
                else ->
                    return exp
            }
        }

        return exp
    }

    /**
     * ```
     * expression4 ::= expression5 (("<" | ">" | "<=" | ">=") expression5)*
     * ```
     */
    private fun parseExpression4(): Expression {
        var exp = parseExpression5()

        while (lexer.hasMore) {
            val location = lexer.nextTokenLocation()
            when {
                lexer.readNextIf(Operator.LessThan) ->
                    exp = Expression.Op(exp, Operator.LessThan, parseExpression5(), location)
                lexer.readNextIf(Operator.LessThanOrEqual) ->
                    exp = Expression.Op(exp, Operator.LessThanOrEqual, parseExpression5(), location)
                lexer.readNextIf(Operator.GreaterThan) ->
                    exp = Expression.Op(exp, Operator.GreaterThan, parseExpression5(), location)
                lexer.readNextIf(Operator.GreaterThanOrEqual) ->
                    exp = Expression.Op(exp, Operator.GreaterThanOrEqual, parseExpression5(), location)
                else ->
                    return exp
            }
        }

        return exp
    }

    /**
     * ```
     * expression5 ::= expression6 (("+" | "-") expression6)*
     * ```
     */
    private fun parseExpression5(): Expression {
        var exp = parseExpression6()

        while (lexer.hasMore) {
            val location = lexer.nextTokenLocation()
            when {
                lexer.readNextIf(Operator.Plus) ->
                    exp = Expression.Op(exp, Operator.Plus, parseExpression6(), location)
                lexer.readNextIf(Operator.Minus) ->
                    exp = Expression.Op(exp, Operator.Minus, parseExpression6(), location)
                else ->
                    return exp
            }
        }

        return exp
    }

    /**
     * ```
     * expression6 ::= expression7 (("*" | "/") expression7)*
     * ```
     */
    private fun parseExpression6(): Expression {
        var exp = parseExpression7()

        while (lexer.hasMore) {
            val location = lexer.nextTokenLocation()
            when {
                lexer.readNextIf(Operator.Multiply) ->
                    exp = Expression.Op(exp, Operator.Multiply, parseExpression7(), location)
                lexer.readNextIf(Operator.Divide) ->
                    exp = Expression.Op(exp, Operator.Divide, parseExpression7(), location)
                else ->
                    return exp
            }
        }

        return exp
    }

    /**
     * ```
     * expression7 ::= expression8 [ '(' args ')']
     * ```
     */
    private fun parseExpression7(): Expression {
        return parseExpression8()

//        return if (lexer.nextTokenIs(LeftParen))
//            Expression.Call(exp, parseArgumentList())
//        else
//            exp
    }

    /**
     * ```
     * expression8 ::= identifier | literal | not | "(" expression ")" | if | while
     * ```
     */
    private fun parseExpression8(): Expression {
        val (token, location) = lexer.peekToken()

        return when (token) {
            is Sym              -> parseIdentifierOrCall()
            is Token.Str        -> parseString()
            is Token.Integer    -> parseInteger()
            Nil                 -> parseNil()
            LeftParen           -> inParens { parseTopLevelExpression() }
            If                  -> parseIf()
            While               -> parseWhile()
            For                 -> parseFor()
            Let                 -> parseLet()
            Operator.Minus      -> parseUnaryMinus()
            else                -> fail(location, "unexpected token $token")
        }
    }

    private fun parseUnaryMinus(): Expression {
        val pos = lexer.expect(Operator.Minus)

        val exp = parseExpression0()

        return Expression.Op(Expression.Int(0), Operator.Minus, exp, pos)
    }

    private fun parseNil(): Expression {
        lexer.expect(Nil)
        return Expression.Nil
    }

    private fun parseInteger(): Expression {
        val token = lexer.readExpected<Token.Integer>().token
        return Expression.Int(token.value)
    }

    private fun parseString(): Expression {
        val (token, location) = lexer.readExpected<Token.Str>()
        return Expression.String(token.value, location)
    }

    private fun parseAssignTo(variable: Variable): Expression {
        val location = lexer.expect(Assign)
        val rhs = parseExpression0()

        return Expression.Assign(variable, rhs, location)
    }

    private fun parseIf(): Expression {
        val location = lexer.expect(If)
        val condition = parseExpression0()
        lexer.expect(Then)
        val consequent = parseExpression0()
        val alternative = if (lexer.readNextIf(Else)) parseExpression0() else null

        return Expression.If(condition, consequent, alternative, location)
    }

    private fun parseLet(): Expression {
        val location = lexer.expect(Let)
        val decls = parseDeclarations()
        lexer.expect(In)
        val body = parseTopLevelExpression()
        lexer.expect(End)

        return Expression.Let(decls, body, location)
    }

    private fun parseWhile(): Expression {
        val location = lexer.expect(While)
        val condition = parseTopLevelExpression()
        lexer.expect(Do)
        val body = parseExpression0()

        return Expression.While(condition, body, location)
    }

    private fun parseFor(): Expression {
        val location = lexer.expect(For)
        val variable = parseName().first
        lexer.expect(Assign)
        val lo = parseExpression0()
        lexer.expect(To)
        val hi = parseExpression0()
        lexer.expect(Do)
        val body = parseExpression0()

        return Expression.For(variable, lo, hi, body, location)
    }

    private fun parseIdentifierOrCall(): Expression {
        val (name, location) = parseName()

        if (lexer.nextTokenIs(LeftParen)) {
            val args = parseArgumentList()
            return Expression.Call(name, args, location)
        } else if (lexer.nextTokenIs(LeftBrace)) {
            val fields = inBraces { separatedBy(Comma) { parseFieldDef() }}
            return Expression.Record(fields, name, location)
        }

        val base: Variable = Variable.Simple(name, location)

        // TODO: generalize this to handle chains
        if (lexer.readNextIf(Period)) {
            val (field, pos) = parseName()
            return Expression.Var(Variable.Field(base, field, pos))
        } else {

            val subscripts = mutableListOf<Expression>()
            while (lexer.nextTokenIs(LeftBracket))
                subscripts += inBrackets { parseTopLevelExpression() }

            if (subscripts.isEmpty()) {
                return Expression.Var(base)
            } else {
                if (lexer.readNextIf(Of)) {
                    val init = parseExpression0()
                    if (subscripts.size == 1)
                        return Expression.Array(name, subscripts[0], init, location)
                    else
                        error("multidimensional arrays are not supported")
                } else {
                    var v = base
                    for (sub in subscripts)
                        v = Variable.Subscript(v, sub, location) // TODO: use location of subscript

                    return Expression.Var(v)
                }
            }
        }
    }

    private fun parseFieldDef(): FieldDef {
        val (name, pos) = parseName()
        lexer.expect(Equal)
        val exp = parseExpression0()
        return FieldDef(name, exp, pos)
    }

    private fun parseArgumentList(): List<Expression> =
        inParens {
            if (lexer.nextTokenIs(RightParen))
                emptyList()
            else {
                val args = ArrayList<Expression>()
                do {
                    args += parseTopLevelExpression()
                } while (lexer.readNextIf(Comma))
                args
            }
        }

    private fun parseArgumentDefinitionList(): List<Field> =
        inParens {
            if (lexer.nextTokenIs(RightParen))
                emptyList()
            else {
                val args = ArrayList<Field>()
                do {
                    val (name, location) = parseName()
                    lexer.expect(Colon)
                    val type = parseName().first
                    args += Field(name, type, location)
                } while (lexer.readNextIf(Comma))
                args
            }
        }

    private fun parseName(): Pair<Symbol, SourceLocation> {
        val (token, location) = lexer.readExpected<Sym>()

        return Pair(token.name, location)
    }

    private fun parseType(): TypeRef = when {
        lexer.readNextIf(Array) -> {
            lexer.expect(Of)

            val (token, location) = lexer.readExpected<Sym>()
            TypeRef.Array(token.name, location)
        }
        lexer.nextTokenIs(LeftBrace) -> {
            TypeRef.Record(inBraces { parseFields() })
        }
        else -> {
            val (token, location) = lexer.readExpected<Sym>()
            TypeRef.Name(token.name, location)
        }
    }

    private fun parseFields(): List<Field> = separatedBy(Token.Punctuation.Comma) {
        val (name, pos) = parseName()
        lexer.expect(Colon)
        val type = parseName().first
        Field(name, type, pos)
    }

    private inline fun <T> inParens(parser: () -> T): T =
        between(LeftParen, RightParen, parser)

    private inline fun <T> inBraces(parser: () -> T): T =
        between(LeftBrace, RightBrace, parser)

    private inline fun <T> inBrackets(parser: () -> T): T =
        between(LeftBracket, RightBracket, parser)

    private inline fun <T> between(left: Token, right: Token, parser: () -> T): T {
        lexer.expect(left)
        val value = parser()
        lexer.expect(right)
        return value
    }

    private inline fun <T> separatedBy(separator: Token, parser: () -> T): List<T> {
        val result = ArrayList<T>()

        do {
            result += parser()
        } while (lexer.readNextIf(separator))

        return result
    }

    private fun fail(location: SourceLocation, message: String): Nothing =
        throw SyntaxErrorException(message, location)

    fun expectEnd() {
        if (lexer.hasMore) {
            val (token, location) = lexer.peekToken()
            fail(location, "expected <eof>, but got $token")
        }
    }
}
