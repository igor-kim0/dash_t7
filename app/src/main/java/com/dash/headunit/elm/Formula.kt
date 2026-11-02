package com.dash.headunit.elm

/**
 * Формула пересчёта байтов ответа из JSON-профиля, например
 * «((A*256)+B)/4» или «(A-128)*100/128». A, B, C… — байты данных ответа
 * (после идентификатора ответа «41 0C» / «62 12 34»).
 *
 * Поддерживаются числа, скобки, унарный минус, + − * / %, а также
 * битовые & | ^ << >> (над целой частью) — их часто используют
 * для флагов. Приоритет как в C. Формула разбирается один раз
 * ([compile]), вычисление — без разбора строки.
 */
class Formula private constructor(val source: String, private val root: Node) {

    /** Значение по байтам [bytes]; null — нужного байта нет или деление на 0. */
    fun eval(bytes: List<Int>): Double? =
        try {
            root.eval(bytes).takeIf { it.isFinite() }
        } catch (e: MissingByte) {
            null
        }

    /** Сколько байтов данных нужно формуле (по самой «дальней» букве). */
    val bytesNeeded: Int = root.maxByte() + 1

    private class MissingByte : RuntimeException()

    private sealed class Node {
        abstract fun eval(b: List<Int>): Double
        abstract fun maxByte(): Int

        class Num(val v: Double) : Node() {
            override fun eval(b: List<Int>) = v
            override fun maxByte() = -1
        }

        class Byte(val index: Int) : Node() {
            override fun eval(b: List<Int>) = (b.getOrNull(index) ?: throw MissingByte()).toDouble()
            override fun maxByte() = index
        }

        class Neg(val x: Node) : Node() {
            override fun eval(b: List<Int>) = -x.eval(b)
            override fun maxByte() = x.maxByte()
        }

        class Bin(val op: String, val l: Node, val r: Node) : Node() {
            override fun eval(b: List<Int>): Double {
                val x = l.eval(b)
                val y = r.eval(b)
                return when (op) {
                    "+" -> x + y
                    "-" -> x - y
                    "*" -> x * y
                    "/" -> x / y
                    "%" -> x % y
                    "&" -> (x.toLong() and y.toLong()).toDouble()
                    "|" -> (x.toLong() or y.toLong()).toDouble()
                    "^" -> (x.toLong() xor y.toLong()).toDouble()
                    "<<" -> (x.toLong() shl y.toInt()).toDouble()
                    ">>" -> (x.toLong() shr y.toInt()).toDouble()
                    else -> error(op)
                }
            }

            override fun maxByte() = maxOf(l.maxByte(), r.maxByte())
        }
    }

    /** Рекурсивный спуск: уровни приоритета от слабого к сильному. */
    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Node {
            val n = binary(0)
            skip()
            if (i < s.length) fail("лишний символ «${s[i]}»")
            return n
        }

        private fun binary(level: Int): Node {
            if (level == LEVELS.size) return unary()
            var left = binary(level + 1)
            while (true) {
                skip()
                val op = LEVELS[level].firstOrNull { s.startsWith(it, i) } ?: return left
                i += op.length
                left = Node.Bin(op, left, binary(level + 1))
            }
        }

        private fun unary(): Node {
            skip()
            if (i < s.length && s[i] == '-') {
                i++
                return Node.Neg(unary())
            }
            if (i < s.length && s[i] == '+') {
                i++
                return unary()
            }
            return atom()
        }

        private fun atom(): Node {
            skip()
            if (i >= s.length) fail("неожиданный конец")
            val c = s[i]
            return when {
                c == '(' -> {
                    i++
                    val n = binary(0)
                    skip()
                    if (i >= s.length || s[i] != ')') fail("нет «)»")
                    i++
                    n
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    Node.Num(s.substring(start, i).toDoubleOrNull() ?: fail("число «${s.substring(start, i)}»"))
                }
                c in 'A'..'Z' && (i + 1 >= s.length || !s[i + 1].isLetterOrDigit()) -> {
                    i++
                    Node.Byte(c - 'A')
                }
                else -> fail("непонятный символ «$c»")
            }
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun fail(msg: String): Nothing =
            throw IllegalArgumentException("формула «$s»: $msg (позиция ${i + 1})")

        companion object {
            /** Двухсимвольные операторы раньше односимвольных. */
            val LEVELS = listOf(
                listOf("|"),
                listOf("^"),
                listOf("&"),
                listOf("<<", ">>"),
                listOf("+", "-"),
                listOf("*", "/", "%"),
            )
        }
    }

    companion object {
        /** Разобрать формулу; IllegalArgumentException с понятным текстом при ошибке. */
        fun compile(source: String): Formula = Formula(source, Parser(source.trim()).parse())
    }
}
