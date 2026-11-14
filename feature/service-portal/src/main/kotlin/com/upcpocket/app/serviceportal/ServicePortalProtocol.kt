package com.upcpocket.app.serviceportal

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// Read-only protocol adapted from UPC-MCP service_portal_protocol.py at
// 948f5f21ad7a12fd94b53403a07ac867209f7700. MIT, Copyright (c) 2026 3beuml.
internal object ServicePortalProtocol {
    private val json = Json { isLenient = false; ignoreUnknownKeys = true }

    fun normalizeSearchKeyword(raw: String): String = normalizeKeyword(raw)

    fun catalog(body: String, scope: ServicePortalScope): ServicePortalCatalog {
        val d = success(body).objectAt("d")
        val rows = d.arrayAt("list")
        val parsed = rows.mapIndexed { index, item -> parseDirectoryRow(index, item) }
        val idCounts = parsed.groupingBy { it.id }.eachCount()
        val folders = parsed.count { it.folder }
        val items = parsed.asSequence()
            .filterNot { it.folder }
            .map { row ->
                ServicePortalItem(
                    rowKey = row.index,
                    detailId = row.id,
                    name = row.name,
                    department = row.department,
                    access = accessKind(row.type, row.jumpType, row.url, row.folder, hasForm = false),
                    externalHost = safeHost(row.url),
                    idAmbiguous = idCounts.getValue(row.id) > 1,
                )
            }
            .toList()
        return ServicePortalCatalog(items, folders, scope)
    }

    fun search(
        catalog: ServicePortalCatalog,
        rawKeyword: String,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): ServicePortalSearchPage {
        validatePage(page, pageSize)
        val keyword = normalizeKeyword(rawKeyword)
        val terms = splitUnicodeWhitespace(keyword).map(::caseFold)
        val matched = catalog.items.filter { item ->
            val searchable = caseFold("${item.name} ${item.department.orEmpty()}")
            terms.all(searchable::contains)
        }
        val total = matched.size
        val pagesTotal = pageCount(total, pageSize)
        val pageInRange = page <= pagesTotal
        val offset = (page - 1) * pageSize
        val items = if (pageInRange) matched.drop(offset).take(pageSize) else emptyList()
        val expected = minOf(pageSize, (total - offset).coerceAtLeast(0))
        val completeForPage = pageInRange && items.size == expected
        val hasMore = offset + items.size < total
        return ServicePortalSearchPage(
            total = total,
            page = page,
            pageSize = pageSize,
            items = items,
            hasMore = hasMore,
            completeForPage = completeForPage,
            completeForScope = true,
            pageInRange = pageInRange,
            scope = catalog.source,
        )
    }

    fun guide(
        body: String,
        item: ServicePortalItem,
        scope: ServicePortalScope,
    ): ServicePortalGuide {
        val d = success(body).objectAt("d")
        if (d.long("id", minimum = 1) != item.detailId) protocolError()
        val title = safeGuideField(d.string("name", maximumCodePoints = 200), 200) ?: protocolError()
        val rawDescription = d.string("explain", required = false, maximumCodePoints = MAX_RESPONSE_CHARS).orEmpty()
        val descriptionResult = htmlText(rawDescription, MAX_DESCRIPTION_CHARS)
        val department = safeGuideField(item.department, 200)
        val materialElement = d["detailed_information"]
        val (materials, materialsState, materialsTruncated) = parseMaterials(materialElement)
        val forms = d.optionalArray("forms")
        val startNodes = d.optionalArray("start_node").orEmpty().asSequence()
            .map { element -> element.asObjectOrNull() ?: protocolError() }
            .mapNotNull { row -> safeGuideField(row["node_name"].textOrNull(80), 80) }
            .filter(String::isNotBlank)
            .take(5)
            .toList()
        val rawUrl = d["url_pc"].textOrNull(MAX_RESPONSE_CHARS)
            ?.takeIf(String::isNotBlank)
            ?: d["url"].textOrNull(MAX_RESPONSE_CHARS)?.takeIf(String::isNotBlank)
        val url = safeExternalUrl(rawUrl)
        return ServicePortalGuide(
            title = title,
            department = department,
            description = descriptionResult.text,
            descriptionTruncated = descriptionResult.truncated || rawDescription.codePointCount(0, rawDescription.length) > MAX_DESCRIPTION_CHARS,
            materials = materials,
            materialsState = materialsState,
            materialsTruncated = materialsTruncated,
            handlingPlace = safeGuideField(d.string("handle_place", false, 200), 200),
            handlingTimes = safeGuideField(d.string("handling_times", false, 200), 200),
            chargeType = safeGuideField(d.string("is_charge", false, 80), 80),
            charge = safeGuideField(d.string("charge", false, 120), 120),
            formCount = forms?.size,
            startNodes = startNodes,
            access = accessKind(
                d["type"].intOrNull(),
                d["jump_type"].intOrNull(),
                rawUrl,
                folder = false,
                hasForm = forms?.isNotEmpty() == true,
            ),
            externalHost = url?.host,
            externalUrl = url?.safeUrl,
            selectionMayBeAmbiguous = item.idAmbiguous || !title.equals(item.name, ignoreCase = true),
            scope = scope,
        )
    }

    fun todos(body: String, page: Int, pageSize: Int = DEFAULT_PAGE_SIZE): ServicePortalDataPage<ServicePortalTodo> {
        return personalPage(body, page, pageSize) { row, index ->
            val status = row["task_status"].scalarTextOrNull(80)
                ?: row["status"].intOrNull()?.toString()
            ServicePortalTodo(
                rowKey = "todo-$page-$index",
                title = row.firstText("task_name", "app_name", "tag_name", limit = 200),
                status = status,
                currentNode = row["node_name"].textOrNull(80)?.takeIf(String::isNotBlank),
                createdAt = row.firstTextOrNull("created", "send_time", limit = 40),
                thirdParty = row["thirdparty_id"].pythonTruthy(),
            )
        }
    }

    fun applications(
        body: String,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): ServicePortalDataPage<ServicePortalApplication> = personalPage(body, page, pageSize) { row, index ->
        val nodesElement = row["nodes"]
        val nodes = when {
            nodesElement == null || nodesElement === JsonNull -> null
            nodesElement is JsonArray -> nodesElement
            else -> protocolError()
        }
        val percentElement = row["percent"]
        val percent = when {
            percentElement == null || percentElement === JsonNull -> null
            else -> percentElement.intOrNull() ?: protocolError()
        }
        if (percent != null && percent !in 0..100) protocolError()
        val title = row.firstText("thirdparty_name", "app_name", "task_name", limit = 200)
        ServicePortalApplication(
            rowKey = "application-$page-$index",
            title = title,
            status = row["inst_status"].textOrNull(80)?.takeIf(String::isNotBlank),
            statusCode = row["status"].intOrNull(),
            createdAt = row["created"].textOrNull(40)?.takeIf(String::isNotBlank),
            thirdParty = row["thirdparty_id"].pythonTruthy(),
            progress = if (!nodes.isNullOrEmpty() && percent != null) ServicePortalProgress(percent, nodes.size) else null,
        )
    }

    private fun <T> personalPage(
        body: String,
        page: Int,
        pageSize: Int,
        project: (JsonObject, Int) -> T,
    ): ServicePortalDataPage<T> {
        validatePage(page, pageSize)
        val d = success(body).objectAt("d")
        val rows = d.arrayAt("list")
        val total = d.long("total", minimum = 0).toIntOrProtocolError()
        if (rows.size > pageSize) protocolError()
        val offset = (page - 1) * pageSize
        val pageInRange = page <= pageCount(total, pageSize)
        if ((pageInRange && offset + rows.size > total) || (!pageInRange && rows.isNotEmpty())) protocolError()
        val items = rows.mapIndexed { index, element ->
            val row = element.asObjectOrNull() ?: protocolError()
            project(row, index)
        }
        val expected = if (pageInRange) minOf(pageSize, (total - offset).coerceAtLeast(0)) else 0
        val completeForPage = pageInRange && items.size == expected
        val hasMore = offset + items.size < total
        val completeForScope = pageInRange && page == 1 && completeForPage && !hasMore
        return ServicePortalDataPage(
            total = total,
            page = page,
            pageSize = pageSize,
            items = items,
            hasMore = hasMore,
            completeForPage = completeForPage,
            completeForScope = completeForScope,
            pageInRange = pageInRange,
            scope = ServicePortalScope.ACCOUNT,
        )
    }

    private fun parseDirectoryRow(index: Int, element: JsonElement): DirectoryRow {
        val row = element.asObjectOrNull() ?: protocolError()
        val id = row.long("id", minimum = 1)
        val name = row.string("name", maximumCodePoints = 200)?.trimUnicodeWhitespace()?.takeIf(String::isNotEmpty)
            ?: protocolError()
        val folder = row["is_folder"].boolish()
        return DirectoryRow(
            index = index,
            id = id,
            name = name,
            department = row.string("departName", false, 200)?.trimUnicodeWhitespace()?.takeIf(String::isNotEmpty),
            type = row["type"].intOrNull(),
            jumpType = row["jump_type"].intOrNull(),
            url = row["url_pc"].textOrNull(MAX_RESPONSE_CHARS)?.takeIf(String::isNotBlank)
                ?: row["url"].textOrNull(MAX_RESPONSE_CHARS)?.takeIf(String::isNotBlank),
            folder = folder,
        )
    }

    private fun accessKind(type: Int?, jumpType: Int?, url: String?, folder: Boolean, hasForm: Boolean): ServicePortalAccess {
        if (folder) return ServicePortalAccess.UNKNOWN
        if (type == 4 && jumpType == 2) return ServicePortalAccess.MINI_PROGRAM
        if (type == 4 && jumpType == 1 && safeExternalUrl(url) != null) return ServicePortalAccess.EXTERNAL
        if (type in setOf(1, 2) && hasForm) return ServicePortalAccess.NATIVE_FORM
        if (type == 1) return ServicePortalAccess.NATIVE_FORM
        if (type == 2) return ServicePortalAccess.FORM_UNKNOWN
        if (type == null) return ServicePortalAccess.UNKNOWN
        return ServicePortalAccess.GUIDE_ONLY
    }

    private data class MaterialParse(
        val items: List<ServicePortalMaterial>,
        val state: ServicePortalMaterialsState,
        val truncated: Boolean,
    )

    private fun parseMaterials(element: JsonElement?): MaterialParse {
        if (element == null || element === JsonNull) {
            return MaterialParse(emptyList(), ServicePortalMaterialsState.MISSING, false)
        }
        val array = element as? JsonArray ?: protocolError()
        if (array.isEmpty()) return MaterialParse(emptyList(), ServicePortalMaterialsState.EMPTY, false)
        val output = mutableListOf<ServicePortalMaterial>()
        var truncated = false
        array.forEach { entry ->
            val row = entry.asObjectOrNull() ?: protocolError()
            val field = row["field"].textOrNull(80).orEmpty()
            if (field in setOf("person", "person_tel")) return@forEach
            val label = safeGuideField(row["label"].textOrNull(120), 120) ?: safeGuideField(field, 80).orEmpty()
            val raw = row["value"].textOrNull(MAX_RESPONSE_CHARS).orEmpty()
            val value = htmlText(raw, MAX_MATERIAL_CHARS)
            if (label.isNotEmpty() || value.text.isNotEmpty()) {
                if (output.size == MAX_MATERIAL_COUNT) {
                    truncated = true
                    return@forEach
                }
                truncated = truncated || value.truncated
                output += ServicePortalMaterial(label, value.text, value.truncated)
            }
        }
        return MaterialParse(
            output,
            if (output.isEmpty()) ServicePortalMaterialsState.EMPTY else ServicePortalMaterialsState.AVAILABLE,
            truncated,
        )
    }

    private data class HtmlText(val text: String, val truncated: Boolean)

    private fun htmlText(html: String, limit: Int): HtmlText {
        val source = html.replace(Regex("(?s)<!--.*?-->"), "")
        val text = StringBuilder()
        var skipTag: String? = null
        var skipDepth = 0
        Regex("(?is)<[^>]*>|[^<]+").findAll(source).forEach { match ->
            val token = match.value
            if (token.startsWith('<')) {
                val closing = token.startsWith("</")
                val tag = Regex("^</?\\s*([A-Za-z0-9]+)").find(token)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
                    ?: return@forEach
                if (skipTag != null) {
                    if (!closing && tag == skipTag && !token.endsWith("/>")) skipDepth++
                    if (closing && tag == skipTag) {
                        skipDepth--
                        if (skipDepth <= 0) skipTag = null
                    }
                } else if (!closing && tag in SKIP_TAGS) {
                    skipTag = tag
                    skipDepth = if (token.endsWith("/>")) 0 else 1
                    if (skipDepth == 0) skipTag = null
                } else if (skipTag == null && tag in BLOCK_TAGS) {
                    if (tag == "li" && (text.isEmpty() || text.last() == '\n')) text.append("• ")
                    else text.append('\n')
                }
            } else if (skipTag == null) {
                text.append(decodeEntities(token))
            }
        }
        val normalized = text.toString()
            .replace(Regex("[ \\t\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        val points = normalized.codePointCount(0, normalized.length)
        return HtmlText(sanitizeGuideText(takeCodePoints(normalized, limit)), points > limit)
    }

    private fun safeGuideField(raw: String?, limit: Int): String? = raw
        ?.let { htmlText(it, limit).text.trimUnicodeWhitespace() }
        ?.takeIf(String::isNotEmpty)

    private fun sanitizeGuideText(value: String): String {
        var safe = ENCODED_URL_IN_TEXT.replace(value) { redactLink(it.value) }
        safe = URL_IN_TEXT.replace(safe) { redactLink(it.value) }
        safe = BARE_DOMAIN_IN_TEXT.replace(safe) { redactLink(it.value) }
        safe = PRIVATE_ATTACHMENT_PATH_IN_TEXT.replace(safe) { "[附件链接已隐藏]" }
        safe = ENCODED_PRIVATE_FIELD_VALUE.replace(safe) { "${it.groupValues[1]}=[信息已隐藏]" }
        safe = PRIVATE_FIELD_VALUE.replace(safe) { "${it.groupValues[1]}=[信息已隐藏]${it.groupValues[2]}" }
        safe = CAS_ST_TICKET_IN_TEXT.replace(safe) { "[票据已隐藏]" }
        return safe
    }

    private fun redactLink(value: String): String {
        val punctuation = value.takeLastWhile { it in TRAILING_LINK_PUNCTUATION }
        return "[链接已隐藏]$punctuation"
    }

    private fun decodeEntities(value: String): String = ENTITY.replace(value) { match ->
        val key = match.groupValues[1]
        when (key) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> {
                val codePoint = runCatching {
                    if (key.startsWith("#x", ignoreCase = true)) key.drop(2).toInt(16) else key.drop(1).toInt()
                }.getOrNull()
                if (codePoint == null || !Character.isValidCodePoint(codePoint) || codePoint in 0xD800..0xDFFF) ""
                else String(Character.toChars(codePoint))
            }
        }
    }

    private data class SafeExternalUrl(val safeUrl: String, val host: String)

    private fun safeExternalUrl(raw: String?): SafeExternalUrl? {
        if (raw.isNullOrBlank() || raw.length > 4096) return null
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.port !in -1..65535
        ) return null
        val host = uri.host.lowercase(Locale.ROOT)
        // Keep the public entry only as non-navigable data. Query, fragment and path are omitted
        // because they may contain tickets, private attachment routes, or other identifiers.
        val safe = runCatching { URI(uri.scheme.lowercase(Locale.ROOT), null, host, uri.port, "/", null, null).toASCIIString() }
            .getOrNull() ?: return null
        return SafeExternalUrl(safe, host)
    }

    private fun safeHost(raw: String?): String? = safeExternalUrl(raw)?.host

    private data class DirectoryRow(
        val index: Int,
        val id: Long,
        val name: String,
        val department: String?,
        val type: Int?,
        val jumpType: Int?,
        val url: String?,
        val folder: Boolean,
    )

    private fun success(body: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(body).asObjectOrNull() }.getOrNull() ?: protocolError()
        val code = root["e"].coercedIntOrNull() ?: protocolError()
        when (code) {
            0 -> Unit
            10042, 100421 -> throw ServicePortalException(ServicePortalFailure.AUTH_REQUIRED)
            30000 -> throw ServicePortalException(ServicePortalFailure.INTERACTION_REQUIRED)
            else -> throw ServicePortalException(ServicePortalFailure.UPSTREAM)
        }
        return root
    }

    private fun validatePage(page: Int, pageSize: Int) {
        if (page !in 1..MAX_PAGE || pageSize !in 1..MAX_PAGE_SIZE) {
            throw ServicePortalException(ServicePortalFailure.INVALID_INPUT)
        }
    }

    private fun pageCount(total: Int, pageSize: Int) = maxOf(1, ((total.toLong() + pageSize - 1) / pageSize).toInt())

    private fun normalizeKeyword(raw: String): String {
        val value = raw.trimUnicodeWhitespace()
        if (value.codePointCount(0, value.length) > MAX_KEYWORD_CODE_POINTS) {
            throw ServicePortalException(ServicePortalFailure.INVALID_INPUT)
        }
        return value
    }

    private fun caseFold(value: String): String = value.uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    private fun splitUnicodeWhitespace(value: String): List<String> {
        val terms = mutableListOf<String>()
        var start = -1
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val nextOffset = offset + Character.charCount(codePoint)
            if (codePoint.isUnicodeWhitespace()) {
                if (start >= 0) {
                    terms += value.substring(start, offset)
                    start = -1
                }
            } else if (start < 0) {
                start = offset
            }
            offset = nextOffset
        }
        if (start >= 0) terms += value.substring(start)
        return terms
    }

    private fun Int.isUnicodeWhitespace(): Boolean =
        this in 0x0009..0x000D ||
            this == 0x0020 ||
            this == 0x0085 ||
            this == 0x00A0 ||
            this == 0x1680 ||
            this in 0x2000..0x200A ||
            this in 0x2028..0x2029 ||
            this == 0x202F ||
            this == 0x205F ||
            this == 0x3000

    private fun String.trimUnicodeWhitespace(): String {
        var start = 0
        var end = length
        while (start < end) {
            val cp = codePointAt(start)
            if (!cp.isUnicodeWhitespace()) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = codePointBefore(end)
            if (!cp.isUnicodeWhitespace()) break
            end -= Character.charCount(cp)
        }
        return substring(start, end)
    }

    private fun takeCodePoints(value: String, maximum: Int): String {
        val count = value.codePointCount(0, value.length)
        return if (count <= maximum) value else value.substring(0, value.offsetByCodePoints(0, maximum))
    }

    private fun JsonElement?.asObjectOrNull() = this as? JsonObject
    private fun JsonObject.objectAt(key: String) = this[key].asObjectOrNull() ?: protocolError()
    private fun JsonObject.arrayAt(key: String) = this[key] as? JsonArray ?: protocolError()
    private fun JsonObject.optionalArray(key: String): JsonArray? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonArray -> value
        else -> protocolError()
    }

    private fun JsonObject.long(key: String, minimum: Long): Long {
        val value = this[key].coercedLongOrNull() ?: protocolError()
        if (value < minimum) protocolError()
        return value
    }

    private fun JsonElement?.numberLongOrNull(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive === JsonNull) return null
        if (primitive.isString) return null
        val raw = primitive.content
        if (!raw.matches(Regex("-?(?:0|[1-9][0-9]*)"))) return null
        return raw.toLongOrNull()
    }

    private fun JsonElement?.coercedLongOrNull(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive === JsonNull) return null
        val raw = primitive.content
        if (!raw.matches(Regex("-?(?:0|[1-9][0-9]*)"))) return null
        return raw.toLongOrNull()
    }

    private fun JsonElement?.intOrNull(): Int? =
        numberLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun JsonElement?.coercedIntOrNull(): Int? =
        coercedLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun JsonElement?.boolish(): Boolean = when (val value = this) {
        null, JsonNull -> false
        is JsonPrimitive -> when {
            value.isString -> protocolError()
            value.booleanOrNull != null -> value.booleanOrNull == true
            value.intOrNull() != null -> value.intOrNull() != 0
            else -> protocolError()
        }
        else -> protocolError()
    }

    private fun JsonObject.string(
        key: String,
        required: Boolean = true,
        maximumCodePoints: Int,
    ): String? {
        val element = this[key]
        if (element == null || element === JsonNull) {
            if (required) protocolError()
            return null
        }
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: protocolError()
        return takeCodePoints(value, maximumCodePoints)
    }

    private fun JsonElement?.textOrNull(maximumCodePoints: Int): String? {
        if (this == null || this === JsonNull) return null
        val primitive = this as? JsonPrimitive ?: protocolError()
        if (!primitive.isString) protocolError()
        return takeCodePoints(primitive.content, maximumCodePoints)
    }

    private fun JsonElement?.scalarTextOrNull(maximumCodePoints: Int): String? {
        if (this == null || this === JsonNull) return null
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return takeCodePoints(primitive.content, maximumCodePoints).trimUnicodeWhitespace()
        return primitive.intOrNull()?.toString()
    }

    private fun JsonObject.firstText(vararg keys: String, limit: Int): String =
        firstTextOrNull(*keys, limit = limit)?.takeIf(String::isNotBlank) ?: ""

    private fun JsonObject.firstTextOrNull(vararg keys: String, limit: Int): String? =
        keys.firstNotNullOfOrNull { key -> this[key].textOrNull(limit)?.trimUnicodeWhitespace()?.takeIf(String::isNotEmpty) }

    private fun JsonElement?.pythonTruthy(): Boolean = when (this) {
        null, JsonNull -> false
        is JsonPrimitive -> when {
            isString -> content.isNotEmpty()
            booleanOrNull != null -> booleanOrNull == true
            intOrNull() != null -> intOrNull() != 0
            else -> false
        }
        is JsonArray -> isNotEmpty()
        is JsonObject -> isNotEmpty()
    }

    private fun Long.toIntOrProtocolError(): Int =
        if (this !in 0..Int.MAX_VALUE.toLong()) protocolError() else toInt()

    private fun protocolError(): Nothing = throw ServicePortalException(ServicePortalFailure.PROTOCOL)

    private const val DEFAULT_PAGE_SIZE = 20
    private const val MAX_PAGE = 50
    private const val MAX_PAGE_SIZE = 50
    private const val MAX_KEYWORD_CODE_POINTS = 40
    private const val MAX_RESPONSE_CHARS = 2_000_000
    private const val MAX_DESCRIPTION_CHARS = 8_000
    private const val MAX_MATERIAL_CHARS = 1_000
    private const val MAX_MATERIAL_COUNT = 40
    private const val TRAILING_LINK_PUNCTUATION = ".,;:!?，。；：！？、)]}"
    private val SKIP_TAGS = setOf("script", "style", "noscript", "iframe", "svg")
    private val BLOCK_TAGS = setOf("p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "table")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")
    private val URL_IN_TEXT = Regex("""(?i)\b(?:https?|ftp|file|content)://[^\s<>"\x27]+""")
    private val ENCODED_URL_IN_TEXT = Regex("""(?i)\b(?:https?|ftp|file|content)%3a%2f%2f[^\s<>"\x27]+""")
    private val BARE_DOMAIN_IN_TEXT = Regex("""(?i)\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?::[0-9]+)?(?:/[^\s<>"\x27]*|\?[^\s<>"\x27]*)?""")
    private val PRIVATE_ATTACHMENT_PATH_IN_TEXT = Regex("""(?i)/(?:[a-z0-9._~%-]+/)*(?:attachments?|files?|download|upload)(?:/[a-z0-9._~%-]+)*(?:\?[^\s<>"\x27]*|\#[^\s<>"\x27]*)?""")
    private val PRIVATE_FIELD_VALUE = Regex(
        """(?i)(["\x27]?\b(?:ticket|token|access[_-]?token|refresh[_-]?token|authorization|auth|session(?:id)?|jsessionid|file[_-]?id|attachment[_-]?id|inst(?:ance)?[_-]?id)["\x27]?\s*[:=]\s*["\x27]?)[^\s"\x27&,;<>]+(["\x27]?)""",
    )
    private val ENCODED_PRIVATE_FIELD_VALUE = Regex(
        """(?i)\b(ticket|token|access[_-]?token|refresh[_-]?token|file[_-]?id|attachment[_-]?id|inst(?:ance)?[_-]?id)(?:=|%3d)[^%&\s<>]+""",
    )
    private val CAS_ST_TICKET_IN_TEXT = Regex(
        """(?<![A-Za-z0-9_])ST-[!-~]{5,}(?![A-Za-z0-9_])""",
    )
}
