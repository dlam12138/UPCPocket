package com.upcpocket.app.serviceportal

import com.upcpocket.app.upcauth.UpcServicePortalCookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicePortalProtocolTest {
    @Test
    fun `catalog keeps duplicate rows and excludes folders before multi word case folded search`() {
        val catalog = ServicePortalProtocol.catalog(
            """{"e":0,"d":{"list":[
                {"id":7,"name":"Straße Leave Form","departName":"Student Affairs","type":1},
                {"id":7,"name":"Folder for leave","departName":"Student Affairs","is_folder":1},
                {"id":7,"name":"Straße Leave Form","departName":"Student Affairs","type":1},
                {"id":8,"name":"Leave Form","departName":"Different Department","type":4,"jump_type":1,"url":"https://forms.example.org/start?ticket=secret"}
            ]}}""",
            ServicePortalScope.ACCOUNT,
        )

        val first = ServicePortalProtocol.search(catalog, "  STRASSE   affairs  ", page = 1, pageSize = 1)
        val second = ServicePortalProtocol.search(catalog, "  STRASSE   affairs  ", page = 2, pageSize = 1)

        assertEquals(1, catalog.folderCount)
        assertEquals(3, catalog.items.size)
        assertEquals(2, first.total)
        assertEquals(0, first.items.single().rowKey)
        assertEquals(2, second.items.single().rowKey)
        assertEquals(ServicePortalAccess.NATIVE_FORM, first.items.single().access)
        assertTrue(first.items.single().idAmbiguous)
        assertTrue(first.hasMore)
        assertTrue(first.completeForPage)
        assertTrue(first.completeForScope)
        assertEquals(ServicePortalScope.ACCOUNT, first.scope)
        assertEquals(ServicePortalAccess.EXTERNAL, catalog.items.last().access)
        assertEquals("forms.example.org", catalog.items.last().externalHost)
        assertFalse(catalog.items.first().toString().contains("Straße"))
        assertFalse(catalog.toString().contains("Straße"))
        assertFalse(first.toString().contains("Straße"))
    }

    @Test
    fun `search validates unicode code point limit and maximum page and page size`() {
        val catalog = ServicePortalCatalog(emptyList(), 0, ServicePortalScope.PUBLIC)
        assertEquals(40, ServicePortalProtocol.normalizeSearchKeyword("😀".repeat(40)).codePointCount(0, 80))
        assertReason(ServicePortalFailure.INVALID_INPUT) {
            ServicePortalProtocol.search(catalog, "😀".repeat(41), 1)
        }
        assertReason(ServicePortalFailure.INVALID_INPUT) { ServicePortalProtocol.search(catalog, "", 51) }
        assertReason(ServicePortalFailure.INVALID_INPUT) { ServicePortalProtocol.search(catalog, "", 1, 0) }
        assertReason(ServicePortalFailure.INVALID_INPUT) { ServicePortalProtocol.search(catalog, "", 1, 51) }
    }

    @Test
    fun `search splits and trims every Unicode White_Space code point`() {
        val whitespace = listOf(
            '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u0085', '\u00A0',
            '\u1680', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006',
            '\u2007', '\u2008', '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F',
            '\u3000',
        )
        val catalog = ServicePortalProtocol.catalog(
            """{"e":0,"d":{"list":[{"id":7,"name":"alpha beta","departName":"","type":1}]}}""",
            ServicePortalScope.PUBLIC,
        )

        whitespace.forEach { separator ->
            val separatorText = separator.toString()
            val result = ServicePortalProtocol.search(catalog, "a${separatorText}b", page = 1)

            assertEquals("U+${separator.code.toString(16)} splits search terms", 1, result.total)
            assertEquals("U+${separator.code.toString(16)} trims keywords", "a", ServicePortalProtocol.normalizeSearchKeyword("${separatorText}a${separatorText}"))
        }
    }

    @Test
    fun `only documented auth and interaction error codes receive auth classifications`() {
        assertReason(ServicePortalFailure.AUTH_REQUIRED) { ServicePortalProtocol.catalog("""{"e":10042}""", ServicePortalScope.ACCOUNT) }
        assertReason(ServicePortalFailure.AUTH_REQUIRED) { ServicePortalProtocol.catalog("""{"e":100421}""", ServicePortalScope.ACCOUNT) }
        assertReason(ServicePortalFailure.INTERACTION_REQUIRED) { ServicePortalProtocol.catalog("""{"e":30000}""", ServicePortalScope.ACCOUNT) }
        assertReason(ServicePortalFailure.UPSTREAM) { ServicePortalProtocol.catalog("""{"e":43001}""", ServicePortalScope.ACCOUNT) }
        assertReason(ServicePortalFailure.PROTOCOL) { ServicePortalProtocol.catalog("<html>login</html>", ServicePortalScope.ACCOUNT) }
    }

    @Test
    fun `guide sanitizes html keeps selected department and distinguishes absent materials`() {
        val item = item()
        val guide = ServicePortalProtocol.guide(
            """{"e":0,"d":{"id":17,"name":"Student Leave","explain":"<p>Submit <b>online</b> &amp; wait</p><script>secretScript()</script><style>secretStyle</style><noscript>secretNoScript</noscript><iframe>secretFrame</iframe><svg><text>secretSvg</text></svg><li>One</li><li>Two</li>","detailed_information":null,"handle_place":"Main office","url":"https://forms.example.org/private/path?ticket=secret#fragment","type":4,"jump_type":1}}""",
            item,
            ServicePortalScope.ACCOUNT,
        )

        assertEquals("Selected department", guide.department)
        assertTrue(guide.description.contains("Submit online & wait"))
        assertTrue(guide.description.contains("One"))
        assertFalse(guide.description.contains("secret"))
        assertEquals(ServicePortalMaterialsState.MISSING, guide.materialsState)
        assertEquals("Main office", guide.handlingPlace)
        assertEquals("forms.example.org", guide.externalHost)
        assertEquals("https://forms.example.org/", guide.externalUrl)
        assertEquals(ServicePortalAccess.EXTERNAL, guide.access)
        assertFalse(guide.selectionMayBeAmbiguous)
        assertFalse(guide.toString().contains("Student Leave"))
    }

    @Test
    fun `guide hides urls tickets and private attachment references from every displayed text field`() {
        val item = item().let {
            ServicePortalItem(
                rowKey = it.rowKey,
                detailId = it.detailId,
                name = it.name,
                department = "Student Affairs https://private.invalid/department?ticket=department-ticket",
                access = it.access,
                externalHost = it.externalHost,
                idAmbiguous = it.idAmbiguous,
            )
        }
        val guide = ServicePortalProtocol.guide(
            """{"e":0,"d":{
                "id":17,
                "name":"Student Leave https://private.invalid/title?ticket=title-ticket",
                "explain":"<p>Submit the form at https://private.invalid/body/file?ticket=body-ticket; office staff can help.</p><p><a href=\"https://private.invalid/href?ticket=href-ticket\">https://private.invalid/visible?ticket=visible-ticket</a> is optional.</p><p>Encoded https&#58;&#47;&#47;private.invalid/entity?ticket&#61;entity-ticket.</p><p>Reference ticket=plain-ticket before calling.</p>",
                "detailed_information":[{"label":"Copy https://private.invalid/label?ticket=label-ticket","field":"file","value":"Bring one signed copy. See /api/attachments/private/download?fileId=attachment-id&ticket=material-ticket"}],
                "handle_place":"Office A; https://private.invalid/place?ticket=place-ticket",
                "charge":"ticket=charge-ticket",
                "url":"https://forms.example.org/private/start?ticket=external-ticket",
                "type":4,"jump_type":1
            }}""",
            item,
            ServicePortalScope.PUBLIC,
        )

        val displayed = listOfNotNull(
            guide.title,
            guide.department,
            guide.description,
            guide.handlingPlace,
            guide.charge,
            guide.materials.single().label,
            guide.materials.single().value,
            guide.externalHost,
            guide.externalUrl,
        ).joinToString("\n")
        listOf(
            "private.invalid",
            "department-ticket",
            "title-ticket",
            "body-ticket",
            "href-ticket",
            "visible-ticket",
            "entity-ticket",
            "plain-ticket",
            "label-ticket",
            "attachment-id",
            "material-ticket",
            "place-ticket",
            "charge-ticket",
            "external-ticket",
        ).forEach { secret -> assertFalse("must not expose $secret", displayed.contains(secret)) }
        assertTrue(guide.title.contains("Student Leave"))
        assertTrue(guide.department?.contains("Student Affairs") == true)
        assertTrue(guide.description.contains("Submit the form"))
        assertTrue(guide.description.contains("office staff can help"))
        assertTrue(guide.description.contains("is optional"))
        assertTrue(guide.description.contains("before calling"))
        assertTrue(guide.description.contains("[链接已隐藏]"))
        assertTrue(guide.handlingPlace?.contains("Office A") == true)
        assertTrue(guide.materials.single().value.contains("Bring one signed copy"))
        assertTrue(guide.materials.single().value.contains("[附件链接已隐藏]"))
        assertTrue(guide.charge?.contains("[信息已隐藏]") == true)
        assertEquals("forms.example.org", guide.externalHost)
        assertEquals("https://forms.example.org/", guide.externalUrl)
    }

    @Test
    fun `guide hides valid short CAS ST tickets beside Chinese and after entity decoding`() {
        val guide = ServicePortalProtocol.guide(
            """{"e":0,"d":{
                "id":17,
                "name":"Student Leave",
                "explain":"凭证ST-12345已提交。<a href=\"https://private.invalid/ST-hidden-url\">链接说明ST-ABCDE已确认</a>后继续阅读。实体编码ST&#45;FGHIJ继续。材料可在五日前补交。XST-UVWXY 与 ST-ABCD 保持普通文本。Students may submit the form before Friday.",
                "detailed_information":[{"label":"材料ST-KLMNO","field":"document","value":"材料说明ST-PQRST可在办公室提交。"}]
            }}""",
            item(),
            ServicePortalScope.PUBLIC,
        )

        val displayed = listOfNotNull(
            guide.title,
            guide.description,
            guide.materials.single().label,
            guide.materials.single().value,
        ).joinToString("\n")
        listOf(
            "ST-12345",
            "ST-ABCDE",
            "ST-FGHIJ",
            "ST-KLMNO",
            "ST-PQRST",
        ).forEach { ticket -> assertFalse("must not expose $ticket", displayed.contains(ticket)) }
        assertTrue(guide.description.contains("[票据已隐藏]"))
        assertTrue(guide.description.contains("凭证"))
        assertTrue(guide.description.contains("后继续阅读"))
        assertTrue(guide.description.contains("XST-UVWXY"))
        assertTrue(guide.description.contains("ST-ABCD"))
        assertTrue(guide.description.contains("Students may submit the form before Friday"))
        assertTrue(guide.materials.single().value.contains("材料说明"))
        assertTrue(guide.materials.single().value.contains("可在办公室提交"))
    }

    @Test
    fun `guide distinguishes empty and truncated material sections and checks returned id`() {
        val item = item()
        val empty = ServicePortalProtocol.guide(
            """{"e":0,"d":{"id":17,"name":"Student Leave","detailed_information":[]}}""",
            item,
            ServicePortalScope.PUBLIC,
        )
        assertEquals(ServicePortalMaterialsState.EMPTY, empty.materialsState)
        assertReason(ServicePortalFailure.PROTOCOL) {
            ServicePortalProtocol.guide(
                """{"e":0,"d":{"id":17,"name":"Student Leave","forms":"unexpected"}}""",
                item,
                ServicePortalScope.PUBLIC,
            )
        }
        assertReason(ServicePortalFailure.PROTOCOL) {
            ServicePortalProtocol.guide(
                """{"e":0,"d":{"id":17,"name":"Student Leave","start_node":["unexpected"]}}""",
                item,
                ServicePortalScope.PUBLIC,
            )
        }

        val manyMaterials = (0..40).joinToString(",") { index -> """{"label":"Item $index","field":"document","value":"Material $index"}""" }
        val truncated = ServicePortalProtocol.guide(
            """{"e":0,"d":{"id":17,"name":"Student Leave","detailed_information":[$manyMaterials]}}""",
            item,
            ServicePortalScope.PUBLIC,
        )
        assertEquals(40, truncated.materials.size)
        assertTrue(truncated.materialsTruncated)
        assertReason(ServicePortalFailure.PROTOCOL) {
            ServicePortalProtocol.guide("""{"e":0,"d":{"id":18,"name":"Other"}}""", item, ServicePortalScope.PUBLIC)
        }
    }

    @Test
    fun `personal queue pages preserve incomplete and empty range semantics`() {
        val first = ServicePortalProtocol.todos(todoBody(total = 25, rows = 20), page = 1, pageSize = 20)
        assertEquals(25, first.total)
        assertTrue(first.completeForPage)
        assertFalse(first.completeForScope)
        assertTrue(first.hasMore)
        assertEquals(ServicePortalScope.ACCOUNT, first.scope)

        val shortPage = ServicePortalProtocol.todos(todoBody(total = 25, rows = 19), page = 1, pageSize = 20)
        assertFalse(shortPage.completeForPage)
        assertFalse(shortPage.completeForScope)
        assertTrue(shortPage.hasMore)

        val last = ServicePortalProtocol.todos(todoBody(total = 25, rows = 5), page = 2, pageSize = 20)
        assertTrue(last.completeForPage)
        assertFalse(last.completeForScope)
        assertFalse(last.hasMore)

        val empty = ServicePortalProtocol.todos(todoBody(total = 0, rows = 0), page = 1, pageSize = 20)
        assertTrue(empty.completeForPage)
        assertTrue(empty.completeForScope)
        assertFalse(empty.hasMore)
    }

    @Test
    fun `page fifty never claims an unloaded personal range is complete`() {
        val page = ServicePortalProtocol.todos(todoBody(total = 1_500, rows = 20), page = 50, pageSize = 20)
        assertTrue(page.pageInRange)
        assertTrue(page.completeForPage)
        assertTrue(page.hasMore)
        assertFalse(page.completeForScope)
    }

    @Test
    fun `empty page beyond current total is out of range and cannot claim complete scope`() {
        val page = ServicePortalProtocol.todos(todoBody(total = 10, rows = 0), page = 50, pageSize = 20)

        assertFalse(page.pageInRange)
        assertFalse(page.completeForPage)
        assertFalse(page.completeForScope)
        assertFalse(page.hasMore)
        assertReason(ServicePortalFailure.PROTOCOL) {
            ServicePortalProtocol.todos(todoBody(total = 10, rows = 1), page = 50, pageSize = 20)
        }
    }

    @Test
    fun `contradictory totals and oversized personal pages are protocol errors`() {
        assertReason(ServicePortalFailure.PROTOCOL) {
            ServicePortalProtocol.todos(todoBody(total = 2, rows = 3), page = 1, pageSize = 20)
        }
        assertReason(ServicePortalFailure.PROTOCOL) {
            ServicePortalProtocol.todos(todoBody(total = 51, rows = 51), page = 1, pageSize = 50)
        }
    }

    @Test
    fun `todos use fallback fields without retaining internal ids`() {
        val page = ServicePortalProtocol.todos(
            """{"e":0,"d":{"total":1,"list":[{"id":123,"inst_id":"private-instance","task_name":"","app_name":"Approval","task_status":"Pending","node_name":"Department review","send_time":"2026-11-14","thirdparty_id":1}]}}""",
            page = 1,
        )
        val todo = page.items.single()
        assertEquals("Approval", todo.title)
        assertEquals("Pending", todo.status)
        assertEquals("Department review", todo.currentNode)
        assertTrue(todo.thirdParty)
        assertFalse(todo.toString().contains("private-instance"))
        assertFalse(todo.toString().contains("Approval"))
        assertFalse(page.toString().contains("Approval"))
    }

    @Test
    fun `zero progress is known only with nodes and invalid percentages are rejected`() {
        val body = """{"e":0,"d":{"total":3,"list":[
            {"id":1,"app_name":"Zero","nodes":[{"node_name":"A"}],"percent":0},
            {"id":2,"app_name":"No nodes","nodes":[],"percent":60},
            {"id":3,"app_name":"Unknown"}
        ]}}"""
        val page = ServicePortalProtocol.applications(body, page = 1)
        assertEquals(0, page.items[0].progress?.percent)
        assertNull(page.items[1].progress)
        assertNull(page.items[2].progress)
        assertFalse(page.items[0].toString().contains("Zero"))
        assertReason(ServicePortalFailure.PROTOCOL) {
            ServicePortalProtocol.applications(
                """{"e":0,"d":{"total":1,"list":[{"nodes":[{}],"percent":101}]}}""",
                page = 1,
            )
        }
    }

    private fun item() = ServicePortalItem(
        rowKey = 3,
        detailId = 17,
        name = "Student Leave",
        department = "Selected department",
        access = ServicePortalAccess.GUIDE_ONLY,
        externalHost = null,
        idAmbiguous = false,
    )

    private fun todoBody(total: Int, rows: Int): String {
        val items = (0 until rows).joinToString(",") { """{"task_name":"Todo $it"}""" }
        return """{"e":0,"d":{"total":$total,"list":[$items]}}"""
    }

    private fun assertReason(expected: ServicePortalFailure, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected $expected")
        } catch (failure: ServicePortalException) {
            assertEquals(expected, failure.reason)
        }
    }
}
