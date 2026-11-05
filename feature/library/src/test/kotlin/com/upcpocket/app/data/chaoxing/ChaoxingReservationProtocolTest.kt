package com.upcpocket.app.data.chaoxing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaoxingReservationProtocolTest {

    private val selectHtml = """
        <html><body>
        <input type="hidden" id="submit_enc" value="701e930aeb3446eb89de7202655afc3e_341948229"/>
        <script>wfwEngineEnc = ''</script>
        <script>var riskCheckOpen = false</script>
        </body></html>
    """.trimIndent()

    @Test
    fun `select page exposes submit enc and engine field without evaluating page`() {
        val secrets = ChaoxingReservationProtocol.parseSelectPage(selectHtml)
        assertEquals("701e930aeb3446eb89de7202655afc3e_341948229", secrets.submitEnc)
        assertEquals("", secrets.wfwEngineEnc)
        assertFalse(secrets.riskCheckRequired)
    }

    @Test
    fun `official submitVerify shape is ordered bracketed md5`() {
        assertEquals(
            "ea4b2967eab804716d93b3767e38bb31",
            ChaoxingReservationProtocol.enc(
                kind = ChaoxingSeatKind.SEAT,
                deptIdEnc = "abc",
                roomId = 8548,
                day = "2026-11-05",
                startTime = "08:00",
                endTime = "08:30",
                seatNum = "A01",
                submitEnc = "701e930aeb3446eb89de7202655afc3e_341948229",
                wfwEngineEnc = "",
            ),
        )
        assertEquals(
            "56f5acf02d01f50be0b0f761e35d5f29",
            ChaoxingReservationProtocol.enc(
                kind = ChaoxingSeatKind.SEATENGINE,
                deptIdEnc = "ignored",
                roomId = 12814,
                day = "2026-11-05",
                startTime = "08:00",
                endTime = "08:30",
                seatNum = "B02",
                submitEnc = "97515771d3b24d82bb658ca3a5c26a68_341948229",
                wfwEngineEnc = "",
            ),
        )
    }

    @Test
    fun `seatengine params include wfwEngineEnc and enc but seat does not`() {
        val seat = ChaoxingReservationProtocol.submitParams(
            ChaoxingSeatKind.SEAT, "abc", 8548, "2026-11-05",
            "08:00", "08:30", "A01",
            "701e930aeb3446eb89de7202655afc3e_341948229", "",
        )
        val engine = ChaoxingReservationProtocol.submitParams(
            ChaoxingSeatKind.SEATENGINE, "abc", 12814, "2026-11-05",
            "08:00", "08:30", "B02",
            "97515771d3b24d82bb658ca3a5c26a68_341948229", "",
        )
        assertEquals(
            listOf(
                "deptIdEnc", "roomId", "day", "startTime", "endTime",
                "seatNum", "captcha", "wyToken", "enc",
            ),
            seat.keys.toList(),
        )
        assertEquals(
            listOf(
                "roomId", "day", "startTime", "endTime",
                "seatNum", "captcha", "wyToken", "wfwEngineEnc", "enc",
            ),
            engine.keys.toList(),
        )
    }

    @Test
    fun `submit response never turns a malformed answer into failure`() {
        val success = ChaoxingReservationProtocol.parseSubmitResponse(
            """{"success":true,"data":{"seatReserve":{"seatNum":"A01"}}}""",
            "A01",
            "2026-11-05",
        )
        assertTrue(success is ChaoxingReservationResult.Success)

        val captcha = ChaoxingReservationProtocol.parseSubmitResponse(
            """{"success":false,"msg":"请输入验证码"}""",
            "A01",
            "2026-11-05",
        )
        assertTrue(captcha is ChaoxingReservationResult.InteractionRequired)

        val malformed = ChaoxingReservationProtocol.parseSubmitResponse(
            "<html>gateway timeout</html>",
            "A01",
            "2026-11-05",
        )
        assertTrue(malformed is ChaoxingReservationResult.Unknown)
    }

    @Test
    fun `repository submits exactly once with signed form and no retry`() = runBlocking {
        val requests = mutableListOf<ChaoxingHttpRequest>()
        val repository = ChaoxingRepository(
            ChaoxingTransport { request ->
                requests += request
                when (request.method) {
                    "GET" -> ChaoxingHttpResponse(200, selectHtml)
                    "POST" -> ChaoxingHttpResponse(
                        200,
                        """{"success":true,"data":{"seatReserve":{"seatNum":"A01"}}}""",
                    )
                    else -> error("unexpected method")
                }
            },
        )
        val result = repository.submitReservation(
            kind = ChaoxingSeatKind.SEAT,
            cookieHeader = "eai-sess=synthetic",
            roomId = 8548,
            date = "2026-11-05",
            startMinute = 8 * 60,
            endMinute = 8 * 60 + 30,
            seatNum = "A01",
        )
        assertTrue(result is ChaoxingReservationResult.Success)
        assertEquals(2, requests.size)
        assertTrue(requests[0].url.contains("/front/third/apps/seat/select?"))
        assertTrue(requests[1].url.endsWith("/data/apps/seat/submit"))
        val form = requests[1].formBody.orEmpty()
        assertTrue(form.contains("seatNum=A01"))
        assertTrue(form.contains("enc="))
    }
}
