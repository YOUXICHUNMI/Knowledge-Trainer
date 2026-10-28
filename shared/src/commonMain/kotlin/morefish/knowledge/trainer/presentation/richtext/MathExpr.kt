package morefish.knowledge.trainer.presentation.richtext

/** TeX 原子类别，只用来算间距（不参与显示）。 */
enum class AtomClass { Ord, Op, Bin, Rel, Open, Close, Punct, Func, Big }

/**
 * 数学公式语法树。
 *
 * 支持范围是"考试题干里真会出现的写法"：上下标、分式、根式、求和积分、
 * 自适应括号、希腊字母、常用运算符、\text、矩阵/分段。**不支持的一律走
 * [Unsupported] 按源码显示**——宁可看到 `\foo`，也不静默吞掉内容。
 */
sealed interface MathNode {
    data class Sym(val text: String, val cls: AtomClass = AtomClass.Ord, val italic: Boolean = false) : MathNode
    data class Row(val items: List<MathNode>) : MathNode
    data class Frac(val num: MathNode, val den: MathNode) : MathNode
    data class Radical(val index: MathNode?, val body: MathNode) : MathNode
    data class Script(val base: MathNode, val sup: MathNode? = null, val sub: MathNode? = null) : MathNode
    data class Fenced(val open: String, val close: String, val body: MathNode) : MathNode
    data class BigOp(val text: String, val sub: MathNode? = null, val sup: MathNode? = null) : MathNode
    data class Styled(
        val body: MathNode,
        val bold: Boolean = false,
        val upright: Boolean = false,
        val blackboard: Boolean = false,
    ) : MathNode
    data class Words(val text: String) : MathNode
    data class Overline(val body: MathNode) : MathNode
    data class Accent(val mark: String, val body: MathNode) : MathNode
    data class Space(val em: Float) : MathNode
    data class Grid(val env: String, val rows: List<List<MathNode>>) : MathNode
    data class Unsupported(val text: String) : MathNode
}

/**
 * LaTeX 子集解析器。
 *
 * 递归下降，`{...}` 分组、`^`/`_` 绑定到紧邻原子。解析失败不抛异常：
 * 遇到不认识的命令产出 [MathNode.Unsupported]，渲染层原样显示命令名。
 */
class MathSourceParser(private val src: String) {

    private var pos = 0

    fun parse(): MathNode = MathNode.Row(parseList(stopAtBrace = false))

    private fun parseList(stopAtBrace: Boolean): List<MathNode> {
        val items = mutableListOf<MathNode>()
        while (pos < src.length) {
            val c = src[pos]
            if (stopAtBrace && c == '}') break
            if (c == '}') {
                pos++
                continue
            }
            if (c.isWhitespace()) {
                pos++
                continue
            }
            if (c == '&') {
                pos++
                continue
            }
            val atom = parseAtomWithScripts()
            if (atom != null) items += atom
        }
        return items
    }

    private fun parseAtomWithScripts(): MathNode? {
        val base = parseAtom() ?: return null
        var sup: MathNode? = null
        var sub: MathNode? = null
        while (pos < src.length) {
            when (src[pos]) {
                '^', '_' -> {
                    val marker = src[pos]
                    pos++
                    val argument = parseArgument()
                    if (marker == '^') sup = sup?.let { MathNode.Row(listOf(it, argument)) } ?: argument
                    else sub = sub?.let { MathNode.Row(listOf(it, argument)) } ?: argument
                }
                '\'' -> {
                    pos++
                    val prime = MathNode.Sym("\u2032")
                    sup = sup?.let { MathNode.Row(listOf(it, prime)) } ?: prime
                }
                else -> return finish(base, sup, sub)
            }
        }
        return finish(base, sup, sub)
    }

    private fun finish(base: MathNode, sup: MathNode?, sub: MathNode?): MathNode = when {
        sup == null && sub == null -> base
        base is MathNode.BigOp -> base.copy(sup = sup ?: base.sup, sub = sub ?: base.sub)
        else -> MathNode.Script(base, sup, sub)
    }

    private fun parseAtom(): MathNode? {
        if (pos >= src.length) return null
        val c = src[pos]
        return when {
            c == '{' -> {
                pos++
                val items = parseList(stopAtBrace = true)
                if (pos < src.length && src[pos] == '}') pos++
                if (items.isEmpty()) null else if (items.size == 1) items[0] else MathNode.Row(items)
            }
            c == '\\' -> parseCommand()
            c.isDigit() -> {
                val start = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                val text = src.substring(start, pos)
                MathNode.Sym(text, AtomClass.Ord, italic = false)
            }
            c.isLetter() -> {
                pos++
                MathNode.Sym(c.toString(), AtomClass.Ord, italic = true)
            }
            c == '-' -> {
                pos++
                MathNode.Sym("\u2212", AtomClass.Bin)
            }
            c == '*' -> {
                pos++
                MathNode.Sym("\u2217", AtomClass.Bin)
            }
            c == '\'' -> {
                pos++
                MathNode.Sym("\u2032")
            }
            else -> {
                pos++
                MathNode.Sym(c.toString(), classOf(c))
            }
        }
    }

    private fun parseArgument(): MathNode {
        skipSpaces()
        if (pos >= src.length) return MathNode.Row(emptyList())
        return if (src[pos] == '{') {
            pos++
            val items = parseList(stopAtBrace = true)
            if (pos < src.length && src[pos] == '}') pos++
            if (items.isEmpty()) MathNode.Row(emptyList())
            else if (items.size == 1) items[0]
            else MathNode.Row(items)
        } else {
            parseAtom() ?: MathNode.Row(emptyList())
        }
    }

    private fun parseRawGroup(): String {
        skipSpaces()
        if (pos >= src.length || src[pos] != '{') return ""
        pos++
        var depth = 1
        val start = pos
        val builder = StringBuilder()
        while (pos < src.length) {
            val c = src[pos]
            if (c == '\\' && pos + 1 < src.length && (src[pos + 1] == '{' || src[pos + 1] == '}')) {
                builder.append(src[pos + 1])
                pos += 2
                continue
            }
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) {
                    pos++
                    return builder.toString()
                }
            }
            builder.append(c)
            pos++
        }
        return src.substring(start)
    }

    private fun skipSpaces() {
        while (pos < src.length && (src[pos] == ' ' || src[pos] == '\n' || src[pos] == '\t')) pos++
    }

    private fun parseCommand(): MathNode? {
        pos++ // 反斜杠
        if (pos >= src.length) return null
        if (!src[pos].isLetter()) {
            val c = src[pos]
            pos++
            return when (c) {
                '{' -> MathNode.Sym("{", AtomClass.Open)
                '}' -> MathNode.Sym("}", AtomClass.Close)
                '|' -> MathNode.Sym("\u2016")
                '%' -> MathNode.Sym("%")
                '$' -> MathNode.Sym("$")
                '&' -> MathNode.Sym("&")
                '#' -> MathNode.Sym("#")
                '_' -> MathNode.Sym("_")
                '\\' -> MathNode.Sym(" ")
                ',' -> MathNode.Space(0.17f)
                ';' -> MathNode.Space(0.28f)
                ':' -> MathNode.Space(0.22f)
                '!' -> MathNode.Space(-0.17f)
                ' ' -> MathNode.Space(0.25f)
                else -> MathNode.Sym(c.toString())
            }
        }
        val start = pos
        while (pos < src.length && src[pos].isLetter()) pos++
        val name = src.substring(start, pos)
        return command(name)
    }

    private fun command(name: String): MathNode? = when (name) {
        "frac", "dfrac", "tfrac" -> {
            val num = parseArgument()
            val den = parseArgument()
            MathNode.Frac(num, den)
        }

        "sqrt" -> {
            skipSpaces()
            var index: MathNode? = null
            if (pos < src.length && src[pos] == '[') {
                val close = src.indexOf(']', pos)
                if (close > pos) {
                    index = MathSourceParser(src.substring(pos + 1, close)).parse()
                    pos = close + 1
                }
            }
            MathNode.Radical(index, parseArgument())
        }

        "text", "textrm", "textnormal", "mbox", "operatorname" -> {
            val raw = parseRawGroup()
            MathNode.Words(raw.replace("~", "\u00A0"))
        }

        "mathrm", "rm" -> MathNode.Styled(parseArgument(), upright = true)
        "mathbf", "bf", "boldsymbol", "bm" -> MathNode.Styled(parseArgument(), bold = true)
        "mathit", "it" -> MathNode.Styled(parseArgument())
        "mathbb", "Bbb" -> MathNode.Styled(parseArgument(), upright = true, blackboard = true)
        "mathsf", "mathtt" -> MathNode.Styled(parseArgument(), upright = true)

        "left" -> {
            val open = readDelimiter()
            val bodyStart = pos
            val rightIndex = findMatchingRight(bodyStart)
            if (rightIndex < 0) {
                pos = src.length
                MathNode.Unsupported("\\left")
            } else {
                val body = MathSourceParser(src.substring(bodyStart, rightIndex)).parse()
                pos = rightIndex + "\\right".length
                val close = readDelimiter()
                MathNode.Fenced(open, close, body)
            }
        }

        "right" -> null
        "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle", "limits", "nolimits",
        "big", "Big", "bigg", "Bigg", "middle",
        -> null

        "overline" -> MathNode.Overline(parseArgument())
        "bar" -> MathNode.Overline(parseArgument())
        "hat" -> MathNode.Accent("^", parseArgument())
        "widehat" -> MathNode.Accent("^", parseArgument())
        "vec" -> MathNode.Accent("\u2192", parseArgument())
        "tilde" -> MathNode.Accent("~", parseArgument())
        "widetilde" -> MathNode.Accent("~", parseArgument())
        "dot" -> MathNode.Accent("\u02D9", parseArgument())
        "ddot" -> MathNode.Accent("\u00A8", parseArgument())

        "quad" -> MathNode.Space(1f)
        "qquad" -> MathNode.Space(2f)
        "enspace" -> MathNode.Space(0.5f)

        "begin" -> {
            val env = parseRawGroup()
            parseEnvironment(env)
        }

        "end" -> {
            parseRawGroup()
            null
        }

        else -> symbols[name] ?: functions[name]?.let { MathNode.Sym(it, AtomClass.Func) }
            ?: bigOps[name]?.let { MathNode.BigOp(it) }
            ?: MathNode.Unsupported("\\$name")
    }

    private fun findMatchingRight(start: Int): Int {
        var depth = 1
        var i = start
        while (i < src.length) {
            when {
                src.startsWith("\\left", i) -> {
                    depth++
                    i += 5
                }
                src.startsWith("\\right", i) -> {
                    depth--
                    if (depth == 0) return i
                    i += 6
                }
                else -> i++
            }
        }
        return -1
    }

    private fun readDelimiter(): String {
        skipSpaces()
        if (pos >= src.length) return ""
        val c = src[pos]
        if (c == '\\') {
            pos++
            if (pos >= src.length) return ""
            val start = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val name = src.substring(start, pos)
            if (name.isEmpty()) {
                val escaped = src[pos]
                pos++
                return escaped.toString()
            }
            return delimiters[name] ?: name
        }
        pos++
        return if (c == '.') "" else c.toString()
    }

    private fun parseEnvironment(env: String): MathNode {
        val endToken = "\\end{$env}"
        val endIndex = src.indexOf(endToken, pos)
        if (endIndex < 0) {
            val body = src.substring(pos)
            pos = src.length
            return MathNode.Grid(env, splitCells(body))
        }
        val body = src.substring(pos, endIndex)
        pos = endIndex + endToken.length
        return MathNode.Grid(env, splitCells(body))
    }

    private fun splitCells(body: String): List<List<MathNode>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var brace = 0
        var env = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                body.startsWith("\\begin{", i) -> {
                    env++
                    cell.append("\\begin{")
                    i += 7
                }
                body.startsWith("\\end{", i) -> {
                    env--
                    cell.append("\\end{")
                    i += 5
                }
                c == '\\' && i + 1 < body.length && body[i + 1] == '\\' && brace == 0 && env == 0 -> {
                    row += cell.toString()
                    rows += row
                    row = mutableListOf()
                    cell.clear()
                    i += 2
                }
                c == '{' -> {
                    brace++
                    cell.append(c)
                    i++
                }
                c == '}' -> {
                    brace--
                    cell.append(c)
                    i++
                }
                c == '&' && brace == 0 && env == 0 -> {
                    row += cell.toString()
                    cell.clear()
                    i++
                }
                else -> {
                    cell.append(c)
                    i++
                }
            }
        }
        row += cell.toString()
        rows += row
        return rows
            .filter { r -> r.any { it.isNotBlank() } }
            .map { r -> r.map { MathSourceParser(it).parse() } }
    }

    private fun classOf(c: Char): AtomClass = when (c) {
        '=', '<', '>' -> AtomClass.Rel
        '+', '-', '\u00B1', '\u00D7', '\u00F7', '\u2212' -> AtomClass.Bin
        ',', ';' -> AtomClass.Punct
        '(', '[', '{' -> AtomClass.Open
        ')', ']', '}' -> AtomClass.Close
        else -> AtomClass.Ord
    }

    private companion object {
        val functions = mapOf(
            "sin" to "sin", "cos" to "cos", "tan" to "tan", "cot" to "cot", "sec" to "sec", "csc" to "csc",
            "arcsin" to "arcsin", "arccos" to "arccos", "arctan" to "arctan",
            "sinh" to "sinh", "cosh" to "cosh", "tanh" to "tanh",
            "log" to "log", "ln" to "ln", "lg" to "lg", "exp" to "exp",
            "lim" to "lim", "max" to "max", "min" to "min", "sup" to "sup", "inf" to "inf",
            "det" to "det", "gcd" to "gcd", "mod" to "mod", "deg" to "deg",
        )

        val bigOps = mapOf(
            "sum" to "\u2211", "prod" to "\u220F", "coprod" to "\u2210",
            "int" to "\u222B", "iint" to "\u222C", "iiint" to "\u222D", "oint" to "\u222E",
            "bigcup" to "\u22C3", "bigcap" to "\u22C2", "bigoplus" to "\u2A01",
            "bigotimes" to "\u2A02", "bigvee" to "\u22C1", "bigwedge" to "\u22C0",
        )

        val delimiters = mapOf(
            "langle" to "\u27E8", "rangle" to "\u27E9",
            "lbrace" to "{", "rbrace" to "}",
            "vert" to "|", "lvert" to "|", "rvert" to "|",
            "Vert" to "\u2016", "lVert" to "\u2016", "rVert" to "\u2016",
            "lfloor" to "\u230A", "rfloor" to "\u230B",
            "lceil" to "\u2308", "rceil" to "\u2309",
            "backslash" to "\\",
        )

        val symbols: Map<String, MathNode> = buildMap {
            // 希腊字母
            val greek = mapOf(
                "alpha" to "\u03B1", "beta" to "\u03B2", "gamma" to "\u03B3", "delta" to "\u03B4",
                "epsilon" to "\u03F5", "varepsilon" to "\u03B5", "zeta" to "\u03B6", "eta" to "\u03B7",
                "theta" to "\u03B8", "vartheta" to "\u03D1", "iota" to "\u03B9", "kappa" to "\u03BA",
                "lambda" to "\u03BB", "mu" to "\u03BC", "nu" to "\u03BD", "xi" to "\u03BE",
                "omicron" to "\u03BF", "pi" to "\u03C0", "varpi" to "\u03D6", "rho" to "\u03C1",
                "varrho" to "\u03F1", "sigma" to "\u03C3", "varsigma" to "\u03C2", "tau" to "\u03C4",
                "upsilon" to "\u03C5", "phi" to "\u03D5", "varphi" to "\u03C6", "chi" to "\u03C7",
                "psi" to "\u03C8", "omega" to "\u03C9",
                "Gamma" to "\u0393", "Delta" to "\u0394", "Theta" to "\u0398", "Lambda" to "\u039B",
                "Xi" to "\u039E", "Pi" to "\u03A0", "Sigma" to "\u03A3", "Upsilon" to "\u03A5",
                "Phi" to "\u03A6", "Psi" to "\u03A8", "Omega" to "\u03A9",
            )
            greek.forEach { (k, v) -> put(k, MathNode.Sym(v, AtomClass.Ord, italic = false)) }

            // 二元运算符
            val binary = mapOf(
                "cdot" to "\u22C5", "times" to "\u00D7", "div" to "\u00F7", "pm" to "\u00B1",
                "mp" to "\u2213", "ast" to "\u2217", "star" to "\u22C6", "circ" to "\u2218",
                "bullet" to "\u2219", "oplus" to "\u2295", "ominus" to "\u2296", "otimes" to "\u2297",
                "cup" to "\u222A", "cap" to "\u2229", "setminus" to "\u2216",
                "land" to "\u2227", "lor" to "\u2228", "wedge" to "\u2227", "vee" to "\u2228",
            )
            binary.forEach { (k, v) -> put(k, MathNode.Sym(v, AtomClass.Bin)) }

            // 关系符
            val relations = mapOf(
                "le" to "\u2264", "leq" to "\u2264", "ge" to "\u2265", "geq" to "\u2265",
                "ne" to "\u2260", "neq" to "\u2260", "approx" to "\u2248", "equiv" to "\u2261",
                "sim" to "\u223C", "simeq" to "\u2243", "cong" to "\u2245", "propto" to "\u221D",
                "ll" to "\u226A", "gg" to "\u226B", "in" to "\u2208", "notin" to "\u2209",
                "ni" to "\u220B", "subset" to "\u2282", "subseteq" to "\u2286",
                "supset" to "\u2283", "supseteq" to "\u2287",
                "to" to "\u2192", "rightarrow" to "\u2192", "leftarrow" to "\u2190",
                "leftrightarrow" to "\u2194", "Rightarrow" to "\u21D2", "implies" to "\u21D2",
                "Leftarrow" to "\u21D0", "Leftrightarrow" to "\u21D4", "iff" to "\u21D4",
                "mapsto" to "\u21A6", "perp" to "\u22A5", "parallel" to "\u2225", "mid" to "\u2223",
                "because" to "\u2235", "therefore" to "\u2234",
            )
            relations.forEach { (k, v) -> put(k, MathNode.Sym(v, AtomClass.Rel)) }

            // 普通符号 / 定界符
            put("infty", MathNode.Sym("\u221E"))
            put("partial", MathNode.Sym("\u2202"))
            put("nabla", MathNode.Sym("\u2207"))
            put("forall", MathNode.Sym("\u2200"))
            put("exists", MathNode.Sym("\u2203"))
            put("emptyset", MathNode.Sym("\u2205"))
            put("varnothing", MathNode.Sym("\u2205"))
            put("angle", MathNode.Sym("\u2220"))
            put("degree", MathNode.Sym("\u00B0"))
            put("prime", MathNode.Sym("\u2032"))
            put("ldots", MathNode.Sym("\u2026"))
            put("dots", MathNode.Sym("\u2026"))
            put("cdots", MathNode.Sym("\u22EF"))
            put("vdots", MathNode.Sym("\u22EE"))
            put("neg", MathNode.Sym("\u00AC"))
            put("hslash", MathNode.Sym("\u210F"))
            put("micro", MathNode.Sym("\u00B5"))
            put("ell", MathNode.Sym("\u2113"))
            put("langle", MathNode.Sym("\u27E8", AtomClass.Open))
            put("rangle", MathNode.Sym("\u27E9", AtomClass.Close))
            put("vert", MathNode.Sym("|", AtomClass.Ord))
            put("Vert", MathNode.Sym("\u2016", AtomClass.Ord))
            put("lvert", MathNode.Sym("|", AtomClass.Open))
            put("rvert", MathNode.Sym("|", AtomClass.Close))
            put("lfloor", MathNode.Sym("\u230A", AtomClass.Open))
            put("rfloor", MathNode.Sym("\u230B", AtomClass.Close))
            put("lceil", MathNode.Sym("\u2308", AtomClass.Open))
            put("rceil", MathNode.Sym("\u2309", AtomClass.Close))
            put("backslash", MathNode.Sym("\\", AtomClass.Ord))
            put("triangle", MathNode.Sym("\u25B3"))
            put("square", MathNode.Sym("\u25A1"))
            put("checkmark", MathNode.Sym("\u2713"))
        }
    }
}
