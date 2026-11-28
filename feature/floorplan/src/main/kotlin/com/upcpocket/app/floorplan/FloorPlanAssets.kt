package com.upcpocket.app.floorplan

import android.content.Context
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal class FloorPlanDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 仅读取随包内置的矢量数据。
 *
 * 原 PDF 和整页图片不进入 APK；这里不做网络、定位或账号读取。解析失败抛
 * [FloorPlanDataException]，由页面显示可重试的错误，不把空图当成功。
 */
internal object FloorPlanAssets {
    private const val ASSET_DIR = "zhuoyue-floor-plans"
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private const val MAX_SHEETS = 32
    private const val MAX_AREAS = 1_000
    private const val MAX_ROOMS = 5_000
    private const val MAX_POINTS = 4_096
    private const val MAX_SHARED_CONTOURS = 12_000
    private const val MAX_SHARED_POINTS = 100_000
    private const val MAX_LABEL_LENGTH = 120
    private const val MAX_LEVEL_LENGTH = 40
    private const val MAX_ID_LENGTH = 64
    private const val MAX_IMAGE_SIDE = 20_000
    private const val MAX_SOURCE_PAGE = 999
    private const val SHARED_PAGE_ASSET = "page-5.json"
    private val SHARED_PAGE_SHEET_IDS = listOf("a-3", "b-3")
    private val ID_PATTERN = Regex("[a-z0-9][a-z0-9-]*")
    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    @Serializable
    private data class SheetDto(
        val id: String,
        val level: String,
        val buildingLevel: String? = null,
        val sourcePage: Int,
        val width: Int,
        val height: Int,
        val areas: List<AreaDto> = emptyList(),
        val rooms: List<RoomDto> = emptyList(),
    )

    @Serializable
    private data class AreaDto(
        val kind: String,
        val points: List<List<Int>>,
    )

    @Serializable
    private data class RoomDto(
        val label: String,
        val points: List<List<Int>>,
    )

    @Serializable
    private data class SharedPageDto(
        val sourcePage: Int,
        val sheetIds: List<String>,
        val width: Int,
        val height: Int,
        val contours: List<List<List<Int>>>,
    )

    fun parse(building: FloorPlanBuilding, raw: String): List<FloorPlanSheet> {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            fail("平面图资产超过大小上限")
        }
        val sheets = try {
            JSON.decodeFromString<List<SheetDto>>(raw)
        } catch (e: SerializationException) {
            throw FloorPlanDataException("平面图 JSON 解析失败", e)
        } catch (e: IllegalArgumentException) {
            throw FloorPlanDataException("平面图 JSON 解析失败", e)
        }
        if (sheets.isEmpty()) fail("平面图资产没有图层")
        if (sheets.size > MAX_SHEETS) fail("平面图图层过多")

        val ids = HashSet<String>()
        return sheets.map { sheet ->
            val id = sheet.id.trim()
            if (!id.matches(ID_PATTERN) || id.length > MAX_ID_LENGTH) fail("图层 ID 非法：$id")
            if (!id.startsWith("${building.id}-")) fail("图层 ${building.id} 与文件名不一致：$id")
            if (!ids.add(id)) fail("图层 ID 重复：$id")
            val level = sheet.level.trim()
            if (level.isEmpty() || level.length > MAX_LEVEL_LENGTH) fail("图层 $id 缺少有效图注")
            val buildingLevel = sheet.buildingLevel?.trim()?.also { value ->
                if (value.isEmpty() || value.length > MAX_LEVEL_LENGTH) {
                    fail("图层 $id 建筑楼层标注无效")
                }
            }
            if (sheet.sourcePage !in 1..MAX_SOURCE_PAGE) fail("图层 $id 来源页非法")
            if (sheet.width !in 1..MAX_IMAGE_SIDE || sheet.height !in 1..MAX_IMAGE_SIDE) {
                fail("图层 $id 尺寸非法")
            }
            if (sheet.areas.size > MAX_AREAS) fail("图层 $id 区域过多")
            if (sheet.rooms.size > MAX_ROOMS) fail("图层 $id 房间过多")

            FloorPlanSheet(
                id = id,
                building = building,
                level = level,
                buildingLevel = buildingLevel,
                sourcePage = sheet.sourcePage,
                width = sheet.width,
                height = sheet.height,
                areas = sheet.areas.map { area ->
                    val kind = FloorPlanAreaKind.fromJson(area.kind)
                        ?: fail("图层 $id 区域类型未知：${area.kind}")
                    FloorPlanArea(kind, polygon(area.points, sheet.width, sheet.height, "图层 $id 区域"))
                },
                rooms = sheet.rooms.map { room ->
                    val label = room.label.trim()
                    if (label.isEmpty() || label.length > MAX_LABEL_LENGTH) {
                        fail("图层 $id 房号标注无效")
                    }
                    FloorPlanRoom(label, polygon(room.points, sheet.width, sheet.height, "图层 $id 房号 $label"))
                },
            )
        }
    }

    fun catalog(
        rawA: String,
        rawB: String,
        rawPage5: String? = null,
    ): FloorPlanCatalog {
        val sheets = parse(FloorPlanBuilding.A, rawA) + parse(FloorPlanBuilding.B, rawB)
        val sharedLinework = rawPage5?.let { parseSharedPage(it, sheets) }
        return try {
            FloorPlanCatalog(sheets, sharedLinework)
        } catch (e: IllegalArgumentException) {
            throw FloorPlanDataException("平面图图层组合无效", e)
        }
    }

    suspend fun load(context: Context): FloorPlanCatalog = withContext(Dispatchers.IO) {
        catalog(
            rawA = readAsset(context, FloorPlanBuilding.A),
            rawB = readAsset(context, FloorPlanBuilding.B),
            rawPage5 = readAsset(context, SHARED_PAGE_ASSET),
        )
    }

    private fun parseSharedPage(
        raw: String,
        sheets: List<FloorPlanSheet>,
    ): FloorPlanSharedLinework {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            fail("第 5 页线稿资产超过大小上限")
        }
        val page = try {
            JSON.decodeFromString<SharedPageDto>(raw)
        } catch (e: SerializationException) {
            throw FloorPlanDataException("第 5 页线稿 JSON 解析失败", e)
        } catch (e: IllegalArgumentException) {
            throw FloorPlanDataException("第 5 页线稿 JSON 解析失败", e)
        }
        if (page.sourcePage != 5 || page.sheetIds != SHARED_PAGE_SHEET_IDS) {
            fail("第 5 页线稿来源或图层 ID 无效")
        }
        if (page.width !in 1..MAX_IMAGE_SIDE || page.height !in 1..MAX_IMAGE_SIDE) {
            fail("第 5 页线稿尺寸非法")
        }
        val sheetsById = sheets.associateBy { it.id }
        val members = page.sheetIds.map { id ->
            sheetsById[id] ?: fail("第 5 页线稿缺少图层 $id")
        }
        if (members.any { sheet ->
                sheet.sourcePage != page.sourcePage ||
                    sheet.width != page.width || sheet.height != page.height
            }
        ) {
            fail("第 5 页线稿与共用图层的来源或尺寸不一致")
        }
        if (members.any { it.buildingLevel == null }) {
            fail("第 5 页共用图层缺少已核对的建筑楼层")
        }
        if (page.contours.isEmpty() || page.contours.size > MAX_SHARED_CONTOURS) {
            fail("第 5 页线稿轮廓数量非法")
        }
        var totalPoints = 0
        val contours = page.contours.mapIndexed { index, contour ->
            totalPoints += contour.size
            if (totalPoints > MAX_SHARED_POINTS) fail("第 5 页线稿顶点过多")
            polygon(contour, page.width, page.height, "第 5 页线稿轮廓 ${index + 1}")
        }
        return FloorPlanSharedLinework(
            sourcePage = page.sourcePage,
            sheetIds = page.sheetIds,
            width = page.width,
            height = page.height,
            contours = contours,
        )
    }

    private fun readAsset(context: Context, assetName: String): String {
        val bytes = context.assets.open("$ASSET_DIR/$assetName").use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > MAX_FILE_BYTES) fail("平面图资产超过大小上限")
            }
            output.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readAsset(context: Context, building: FloorPlanBuilding): String =
        readAsset(context, building.assetName)

    private fun polygon(
        raw: List<List<Int>>,
        width: Int,
        height: Int,
        description: String,
    ): List<FloorPlanPoint> {
        if (raw.size !in 3..MAX_POINTS) fail("$description 顶点数量非法")
        return raw.map { point ->
            if (point.size != 2) fail("$description 顶点格式非法")
            val x = point[0]
            val y = point[1]
            if (x !in 0..width || y !in 0..height) fail("$description 顶点超出图层")
            FloorPlanPoint(x, y)
        }
    }

    private fun fail(message: String): Nothing = throw FloorPlanDataException(message)
}
