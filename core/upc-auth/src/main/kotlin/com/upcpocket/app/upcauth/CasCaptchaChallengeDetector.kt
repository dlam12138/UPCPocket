package com.upcpocket.app.upcauth

/** Narrow evidence used by both CAS login flows before they submit a password. */
internal object CasCaptchaChallengeDetector {
    data class Result(val active: Boolean, val captchaId: String? = null)

    private val htmlComment = Regex("(?s)<!--.*?-->")
    private val scriptElement = Regex("(?is)<script\\b[^>]*>(.*?)</script\\s*>")
    private val inertElement = Regex("(?is)<(script|style|textarea|template|title)\\b[^>]*>.*?</\\1\\s*>")
    private val htmlTag = Regex("<\\s*(/?)\\s*([A-Za-z][A-Za-z0-9:-]*)\\b([^>]*)>")
    private val htmlAttribute = Regex(
        "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*(?:=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?",
    )
    private val captchaAssignment = Regex(
        "(?i)\\bconfig\\s*\\.\\s*captcha\\s*(\\|\\|=|&&=|\\?\\?=|<<=|>>>=|>>=|\\*\\*=|[+*/%&^|\\-]=|(?<![=!<>])=(?!=|>))",
    )
    private val inactiveCaptchaLiteral = Regex("(?i)^(null|false|undefined)\\b")
    private val captchaId = Regex("(?is)(?:['\"]?id['\"]?)\\s*:\\s*['\"]([^'\"]+)['\"]")
    private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    /** Removes content that the browser does not treat as HTML elements. */
    fun visibleMarkup(html: String): String = inertElement.replace(htmlComment.replace(html, ""), "")

    fun inspect(html: String): Result {
        val clean = htmlComment.replace(html, "")
        val config = scriptElement.findAll(clean)
            .mapNotNull { activeConfiguration(it.groupValues[1]) }
            .firstOrNull()
        return Result(
            active = config != null || hasVisibleCaptchaElement(visibleMarkup(clean)),
            captchaId = config?.second,
        )
    }

    private fun activeConfiguration(script: String): Pair<String, String?>? {
        val syntax = maskJavaScript(script, preserveStrings = false)
        val sourceWithoutComments = maskJavaScript(script, preserveStrings = true)
        captchaAssignment.findAll(syntax).forEach { assignment ->
            if (assignment.groupValues[1] != "=") return "active" to null
            var valueStart = assignment.range.last + 1
            while (valueStart < syntax.length && syntax[valueStart].isWhitespace()) valueStart++
            if (valueStart < syntax.length && syntax[valueStart] == '{') {
                val end = matchingObjectEnd(syntax, valueStart)
                if (end != null) {
                    val objectSource = sourceWithoutComments.substring(valueStart, end + 1)
                    return objectSource to captchaId.find(objectSource)?.groupValues?.get(1)
                }
            }

            val inactiveLiteral = inactiveCaptchaLiteral.find(syntax.substring(valueStart))
            if (inactiveLiteral != null) {
                var trailing = valueStart + inactiveLiteral.range.last + 1
                while (trailing < syntax.length && syntax[trailing].isWhitespace()) trailing++
                if (trailing == syntax.length || syntax[trailing] == ';') return@forEach
            }

            // Unknown expressions and malformed assignments may enable a challenge at runtime.
            return "active" to null
        }
        return null
    }

    private fun matchingObjectEnd(source: String, start: Int): Int? {
        var depth = 0
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    /** Masks JavaScript comments and, optionally, string/template literals without parsing JavaScript. */
    private fun maskJavaScript(source: String, preserveStrings: Boolean): String {
        val chars = source.toCharArray()
        var mode = 0 // 0 code, 1 single quote, 2 double quote, 3 template, 4 line comment, 5 block comment
        var escaped = false
        var index = 0
        fun hide(at: Int) {
            if (chars[at] != '\n' && chars[at] != '\r') chars[at] = ' '
        }

        while (index < chars.size) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when (mode) {
                0 -> when {
                    current == '/' && next == '/' -> {
                        hide(index); hide(index + 1); mode = 4; index += 2; continue
                    }
                    current == '/' && next == '*' -> {
                        hide(index); hide(index + 1); mode = 5; index += 2; continue
                    }
                    current == '\'' || current == '"' || current == '`' -> {
                        mode = when (current) { '\'' -> 1; '"' -> 2; else -> 3 }
                        if (!preserveStrings) hide(index)
                    }
                }
                1, 2, 3 -> {
                    if (!preserveStrings) hide(index)
                    if (escaped) escaped = false
                    else if (current == '\\') escaped = true
                    else if ((mode == 1 && current == '\'') || (mode == 2 && current == '"') || (mode == 3 && current == '`')) mode = 0
                }
                4 -> if (current == '\n' || current == '\r') mode = 0 else hide(index)
                5 -> {
                    hide(index)
                    if (current == '*' && next == '/') {
                        hide(index + 1); mode = 0; index++
                    }
                }
            }
            index++
        }
        return String(chars)
    }

    private fun hasVisibleCaptchaElement(markup: String): Boolean {
        data class Ancestor(val name: String, val hidden: Boolean)

        val ancestors = mutableListOf<Ancestor>()
        for (tag in htmlTag.findAll(markup)) {
            val closing = tag.groupValues[1].isNotEmpty()
            val name = tag.groupValues[2].lowercase()
            if (closing) {
                val match = ancestors.indexOfLast { it.name == name }
                if (match >= 0) while (ancestors.size > match) ancestors.removeAt(ancestors.lastIndex)
                continue
            }

            val attributes = htmlAttribute.findAll(tag.groupValues[3]).associate { match ->
                val value = match.groupValues[2].ifEmpty { match.groupValues[3].ifEmpty { match.groupValues[4] } }
                match.groupValues[1].lowercase() to value
            }
            val parentHidden = ancestors.lastOrNull()?.hidden == true
            val style = attributes["style"].orEmpty()
            val displays = styleValues(style, "display")
            val visibilities = styleValues(style, "visibility")
            val display = displays.lastOrNull()
            val visibility = visibilities.lastOrNull()
            val displayAmbiguous = displays.size > 1
            val visibilityAmbiguous = visibilities.size > 1
            val selfHidden = attributes.containsKey("hidden") ||
                (!displayAmbiguous && display.equals("none", ignoreCase = true)) ||
                (!visibilityAmbiguous && (visibility.equals("hidden", ignoreCase = true) ||
                    visibility.equals("collapse", ignoreCase = true)))
            val hidden = parentHidden || selfHidden
            val id = attributes["id"].orEmpty()
            val fieldName = attributes["name"].orEmpty()
            val type = attributes["type"].orEmpty()

            val activeContainer = name == "div" && id.equals("captchaParent", ignoreCase = true) && !hidden
            val activeInput = name == "input" &&
                (fieldName.equals("captcha", ignoreCase = true) || id.equals("captcha", ignoreCase = true)) &&
                !hidden && !type.equals("hidden", ignoreCase = true)
            val activeImage = name == "img" &&
                id.lowercase() in setOf("captcha", "captchaimg", "captchaimage") && !hidden
            if (activeContainer || activeInput || activeImage) return true

            val selfClosing = tag.groupValues[3].trimEnd().endsWith("/")
            if (name !in voidTags && !selfClosing) ancestors += Ancestor(name, hidden)
        }
        return false
    }

    private fun styleValues(style: String, property: String): List<String> = style.split(';')
        .mapNotNull { declaration ->
            val separator = declaration.indexOf(':')
            if (separator < 0 || !declaration.substring(0, separator).trim().equals(property, ignoreCase = true)) {
                null
            } else {
                declaration.substring(separator + 1).trim()
                    .replace(Regex("(?i)\\s*!important\\s*$"), "").trim()
            }
        }
}
