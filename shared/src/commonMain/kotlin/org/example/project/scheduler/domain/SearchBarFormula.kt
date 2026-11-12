package org.example.project.scheduler.domain

/**
 * User rule 2026-11-06: **a Search window's search bars put together by a formula** — *"“main search bar” AND (“search
 * bar 2” OR “search bar 3”)"*. Bar 1 is the window's own bar ([SearchDomain.Config.query]), the others are the ones the
 * user added ([SearchDomain.Config.extraQueries]), numbered from 2 in their order.
 *
 * A bar is named by its number (`2`), or quoted as the user wrote it (`"main search bar"`, `“search bar 2”`, `"bar 2"`,
 * `"main"`); `AND`, `OR`, `NOT` (or `&&`, `||`, `!`, any case) and parentheses put them together, and two bars side by
 * side are an AND. A blank formula — or one this does not read ([parse] answers null, [problem] says why) — is every
 * bar at once, ANDed, as one bar alone always was. A blank bar answers every element, as the window's bar does.
 */
object SearchBarFormula {
    sealed interface Node {
        data class Bar(val index: Int) : Node
        data class Not(val of: Node) : Node
        data class And(val left: Node, val right: Node) : Node
        data class Or(val left: Node, val right: Node) : Node
    }

    /** Whether the bars, [matches] answering bar `i` (0 = the main bar) of [count], satisfy [formula]. */
    fun evaluate(formula: String, count: Int, matches: (Int) -> Boolean): Boolean {
        val node = parse(formula, count) ?: return (0 until count).all(matches)
        return eval(node, matches)
    }

    private fun eval(node: Node, matches: (Int) -> Boolean): Boolean =
        when (node) {
            is Node.Bar -> matches(node.index)
            is Node.Not -> !eval(node.of, matches)
            is Node.And -> eval(node.left, matches) && eval(node.right, matches)
            is Node.Or -> eval(node.left, matches) || eval(node.right, matches)
        }

    /** What is wrong with [formula] over [count] bars, in words; null when it reads (or is blank). */
    fun problem(formula: String, count: Int): String? {
        if (formula.isBlank()) return null
        return runCatching { Parser(tokens(formula), count).parseAll(); null }.getOrElse { it.message ?: "not understood" }
    }

    /** [formula] read over [count] bars; null when blank or not understood. */
    fun parse(formula: String, count: Int): Node? {
        if (formula.isBlank()) return null
        return runCatching { Parser(tokens(formula), count).parseAll() }.getOrNull()
    }

    private sealed interface Token {
        data class Ref(val name: String) : Token
        object And : Token
        object Or : Token
        object Not : Token
        object Open : Token
        object Close : Token
    }

    private val QUOTES = mapOf('"' to '"', '“' to '”', '«' to '»')

    private fun tokens(text: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { out += Token.Open; i++ }
                c == ')' -> { out += Token.Close; i++ }
                c == '!' -> { out += Token.Not; i++ }
                text.startsWith("&&", i) -> { out += Token.And; i += 2 }
                text.startsWith("||", i) -> { out += Token.Or; i += 2 }
                c in QUOTES -> {
                    val close = QUOTES.getValue(c)
                    val end = text.indexOf(close, i + 1).takeIf { it > i } ?: text.indexOf('"', i + 1).takeIf { it > i }
                        ?: throw IllegalArgumentException("a quote is not closed")
                    out += Token.Ref(text.substring(i + 1, end))
                    i = end + 1
                }
                else -> {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace() && text[end] !in "()!\"“”«»&|") end++
                    if (end == i) throw IllegalArgumentException("“$c” is not understood")
                    val word = text.substring(i, end)
                    out +=
                        when (word.uppercase()) {
                            "AND", "ET" -> Token.And
                            "OR", "OU" -> Token.Or
                            "NOT" -> Token.Not
                            else -> Token.Ref(word)
                        }
                    i = end
                }
            }
        }
        return out
    }

    /** Bar `i` (0-based) a reference names: its number, "main", "main search bar", "search bar N", "bar N". */
    private fun barOf(name: String, count: Int): Int {
        val n = name.trim().lowercase().replace(Regex("\\s+"), " ")
        val number =
            when {
                n == "main" || n == "main search bar" || n == "main bar" || n == "search bar" -> 1
                else -> n.removePrefix("search bar").removePrefix("bar").trim().toIntOrNull()
            } ?: throw IllegalArgumentException("“$name” names no search bar")
        if (number !in 1..count) throw IllegalArgumentException("there is no search bar $number")
        return number - 1
    }

    private class Parser(val tokens: List<Token>, val count: Int) {
        var at = 0

        fun parseAll(): Node {
            if (tokens.isEmpty()) throw IllegalArgumentException("empty")
            val node = or()
            if (at < tokens.size) throw IllegalArgumentException("a parenthesis or an operator is missing")
            return node
        }

        private fun or(): Node {
            var left = and()
            while (tokens.getOrNull(at) == Token.Or) {
                at++
                left = Node.Or(left, and())
            }
            return left
        }

        private fun and(): Node {
            var left = not()
            while (true) {
                val next = tokens.getOrNull(at)
                when {
                    next == Token.And -> { at++; left = Node.And(left, not()) }
                    // Two bars side by side: the AND a search of two words already means.
                    next is Token.Ref || next == Token.Open || next == Token.Not -> left = Node.And(left, not())
                    else -> return left
                }
            }
        }

        private fun not(): Node {
            if (tokens.getOrNull(at) == Token.Not) {
                at++
                return Node.Not(not())
            }
            return atom()
        }

        private fun atom(): Node =
            when (val t = tokens.getOrNull(at++)) {
                is Token.Ref -> Node.Bar(barOf(t.name, count))
                Token.Open -> {
                    val inner = or()
                    if (tokens.getOrNull(at++) != Token.Close) throw IllegalArgumentException("a parenthesis is not closed")
                    inner
                }
                null -> throw IllegalArgumentException("the formula stops too early")
                else -> throw IllegalArgumentException("an operator stands where a search bar is expected")
            }
    }
}
