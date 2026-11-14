package com.upcpocket.app.shiguang

import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder

// Protocol and parser adapted from UPC-MCP shiguang.py at
// 948f5f21ad7a12fd94b53403a07ac867209f7700. MIT, Copyright (c) 2026 3beuml.
// Only read-only contracts are ported. No registration or other mutation endpoint.
internal object ShiguangProtocol {
    fun list(html: String, page: Int): ShiguangPage {
        val root = tree(html)
        val container = root.findClass("active_2_list") ?: fail()
        // .li identifies cards; native <li> descendants can be metadata bullets.
        val cards = container.walk().filter { "li" in it.classes }.toList()
        if (cards.isEmpty() && container.walk().any { it.tag == "li" }) fail()
        val items = cards.map { card ->
            val link = card.walk().firstOrNull { it.tag == "a" && "ellipsis" in it.classes }
                ?: card.walk().firstOrNull { it.tag == "a" && "more" in it.classes } ?: fail()
            val detail = card.walk().firstOrNull { it.tag == "a" && "more" in it.classes } ?: link
            // Upstream falls back to the title link when the more link has no activity ID.
            // Resolve only known same-host detail paths; no raw href is ever sent to HTTP.
            val target = detailTarget(detail.attrs["href"].orEmpty())
                ?: detailTarget(link.attrs["href"].orEmpty()) ?: fail()
            val title = link.text().takeIf { it.isNotBlank() } ?: fail()
            ShiguangActivity(target.first, target.second, title, fields(card.text(), listOf("报名截止时间", "报名时间", "活动时间", "分类", "地点", "学时", "学分")))
        }
        // A container with links but no recognized cards must not silently become empty.
        if (items.isEmpty() && container.walk().any { it.tag == "a" && ("ellipsis" in it.classes || "more" in it.classes) }) fail()
        val pages = Regex("共\\s*([0-9]+)\\s*页").find(root.text())?.groupValues?.get(1)?.toIntOrNull()
        if (pages != null && (pages !in 0..5000 || (items.isNotEmpty() && pages < page))) fail()
        return ShiguangPage(page, pages, items)
    }

    private fun detailTarget(href: String): Pair<String, ShiguangKind>? {
        if (href.isBlank()) return null
        val uri = runCatching { URI("https://dekt.upc.edu.cn/").resolve(href.trim()) }.getOrNull() ?: return null
        if (uri.host != "dekt.upc.edu.cn" || uri.scheme !in setOf("https", "http") || uri.userInfo != null || uri.port !in setOf(-1, 80, 443)) return null
        val kind = when (uri.path) {
            ShiguangEndpoint.ACTIVITY_DETAIL.path -> ShiguangKind.ACTIVITY
            ShiguangEndpoint.PRACTICE_DETAIL.path -> ShiguangKind.SOCIAL_PRACTICE
            else -> return null
        }
        val ids = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
            .filter { it[0] == "actvModel.activityId" }.map { decode(it.getOrElse(1) { "" }) }
        val id = ids.singleOrNull()?.takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: return null
        return id to kind
    }

    fun detail(html: String): ShiguangDetail {
        val left = tree(html).findClass("activeD_l") ?: fail()
        val title = left.findClass("t2")?.text()?.takeIf { it.isNotBlank() } ?: fail()
        val labels = setOf("归属组织", "活动分类", "活动标签", "组织形式", "培养目标", "能力指标", "活动地点", "报名时间", "活动时间", "活动院系", "活动年级", "学时", "学分")
        val fields = linkedMapOf<String, String>()
        left.walk().filter { "li" in it.classes }.forEach { node ->
            val match = Regex("^([^：:]{1,24})[：:]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).find(node.text())
            if (match != null && match.groupValues[1].trim() in labels)
                fields[match.groupValues[1].trim()] = clean(match.groupValues[2])
        }
        return ShiguangDetail(title, left.findClass("djs")?.text()?.takeIf { it.isNotBlank() }, fields)
    }

    fun summary(html: String): ShiguangSummary {
        val inputs = tree(html).walk().filter { it.tag == "input" && "id" in it.attrs }.toList()
        val required = (0..4).flatMap { listOf("credit$it", "credit0$it") } + listOf("flag", "orderNum")
        if (required.any { key -> inputs.count { it.attrs["id"] == key } != 1 }) fail()
        val values = inputs.associate { it.attrs["id"]!! to it.attrs["value"].orEmpty() }
        val order = values.getValue("orderNum").split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull() }
        if (order.sorted() != listOf(1, 2, 3, 4, 5) || values.getValue("orderNum").split(',').filter { it.isNotBlank() }.size != 5) fail()
        val ranks = values.getValue("flag").split(',').map(String::trim)
        if (ranks.size > 10 && ranks.drop(10).any(String::isNotBlank)) fail()
        val labels = listOf("思想成长", "创新创业", "文体发展", "社会实践与志愿服务", "工作履历与技能培训")
        val modules = (1..5).map { id ->
            val p = ranks.getOrNull((id - 1) * 2).orEmpty()
            val t = ranks.getOrNull((id - 1) * 2 + 1).orEmpty()
            val rank = if (p.isBlank() || t.isBlank()) null else {
                val position = p.toIntOrNull() ?: fail(); val total = t.toIntOrNull() ?: fail()
                if (position < 0 || total < 0 || position > total) fail()
                ShiguangRank(position, total)
            }
            ShiguangModule(id, labels[id - 1], decimal(values.getValue("credit${id - 1}")), decimal(values.getValue("credit0${id - 1}")), rank)
        }
        return ShiguangSummary(order.map { id -> modules.first { it.id == id } })
    }

    private fun decimal(raw: String): BigDecimal? {
        if (raw.isBlank()) return null
        val value = raw.trim()
        if (value.length > 128 || !value.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))) fail()
        val number = value.toBigDecimalOrNull() ?: fail()
        // Finite decimal semantics match upstream; bound expansion for native text rendering.
        if (number.scale() !in -128..128) fail()
        return number
    }
    private fun fields(text: String, labels: List<String>): Map<String, String> = buildMap {
        labels.forEach { label -> Regex("${Regex.escape(label)}\\s*[：:]\\s*([^\\n]+)").find(text)?.let { put(label, clean(it.groupValues[1])) } }
    }
    private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrElse { fail() }
    internal fun isLogin(html: String): Boolean {
        val head = html.take(30000).lowercase()
        return (Regex("(?:id\\s*=\\s*['\"]loginform|name\\s*=\\s*['\"]execution|type\\s*=\\s*['\"]password)").containsMatchIn(head)) &&
            ("cas/login" in head || "统一身份认证" in head || "caslogin.jsp" in head)
    }
    private fun tree(html: String): Node {
        if (isLogin(html)) throw ShiguangException(ShiguangFailure.AUTH_REQUIRED)
        val root = Node("root")
        val stack = mutableListOf(root)
        val sanitized = html.replace(Regex("<(script|style)\\b[^>]*>(?:.*?</\\1\\s*>|.*$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
        Regex("<[^>]+>|[^<]+").findAll(sanitized).forEach { token ->
            val raw = token.value
            if (!raw.startsWith('<')) stack.last().parts.add(entities(raw))
            else if (raw.startsWith("</")) {
                val tag = raw.removePrefix("</").substringBefore('>').trim().lowercase()
                val index = stack.indexOfLast { it.tag == tag }
                if (index > 0) while (stack.size > index) stack.removeAt(stack.lastIndex)
            } else if (!raw.startsWith("<!") && !raw.startsWith("<?")) {
                val tag = Regex("^<([A-Za-z0-9]+)").find(raw)?.groupValues?.get(1)?.lowercase() ?: return@forEach
                val attrs = Regex("([A-Za-z_:][A-Za-z0-9_:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").findAll(raw).associate {
                    it.groupValues[1].lowercase() to entities(it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty())
                }
                val node = Node(tag, attrs); stack.last().parts.add(node)
                if (tag !in setOf("input", "img", "br", "hr", "meta", "link", "area", "base", "wbr", "source", "embed") && !raw.endsWith("/>")) stack.add(node)
            }
        }
        return root
    }
    private class Node(val tag: String, val attrs: Map<String, String> = emptyMap()) {
        val parts = mutableListOf<Any>()
        val classes get() = attrs["class"].orEmpty().split(Regex("\\s+")).toSet()
        fun walk(): Sequence<Node> = sequence { parts.filterIsInstance<Node>().forEach { yield(it); yieldAll(it.walk()) } }
        fun findClass(name: String) = walk().firstOrNull { name in it.classes }
        fun text(): String = clean(parts.joinToString("") { if (it is Node) it.text() + if (it.tag in setOf("div", "p", "li", "br", "tr")) "\n" else "" else it.toString() })
    }
    private fun entities(s: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);").replace(s) {
        val v = it.groupValues[1]
        when (v) { "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> runCatching { String(Character.toChars(if (v.startsWith("#x")) v.drop(2).toInt(16) else v.drop(1).toInt())) }.getOrDefault("") }
    }
    // Native text only. Remove contact/phone information even if embedded in the description.
    private fun clean(s: String): String = s.replace(Regex("(?:联系人|联系电话)\\s*[：:][^\\n]*"), "")
        .replace(Regex("(?<![0-9])(?:1[3-9][0-9]{9}|0[0-9]{2,3}[- ]?[0-9]{7,8})(?![0-9])"), "")
        .replace(Regex("[ \\t\\r]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
    private fun fail(): Nothing = throw ShiguangException(ShiguangFailure.PROTOCOL)
}
