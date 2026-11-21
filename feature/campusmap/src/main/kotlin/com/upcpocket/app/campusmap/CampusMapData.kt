package com.upcpocket.app.campusmap

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

enum class CampusId(val code: String, val title: String) {
    TDW("tdw", "唐岛湾"),
    GZK("gzk", "古镇口"),
}

internal data class GeoPoint(val lon: Double, val lat: Double) {
    fun distanceTo(other: GeoPoint): Double {
        val east = (other.lon - lon) * METERS_PER_DEGREE * cos(Math.toRadians((lat + other.lat) / 2))
        val north = (other.lat - lat) * METERS_PER_DEGREE
        return hypot(east, north)
    }

    fun bearingTo(other: GeoPoint): Double {
        val east = (other.lon - lon) * cos(Math.toRadians((lat + other.lat) / 2))
        val north = other.lat - lat
        return (Math.toDegrees(atan2(east, north)) + 360) % 360
    }

    fun interpolate(other: GeoPoint, fraction: Double) = GeoPoint(
        lon = lon + (other.lon - lon) * fraction,
        lat = lat + (other.lat - lat) * fraction,
    )

    fun projectOnto(a: GeoPoint, b: GeoPoint): PathProjection {
        val longitudeScale = cos(Math.toRadians((a.lat + b.lat) / 2))
        val dx = (b.lon - a.lon) * longitudeScale
        val dy = b.lat - a.lat
        val length = dx * dx + dy * dy
        val fraction = if (length == 0.0) 0.0 else (
            ((lon - a.lon) * longitudeScale * dx + (lat - a.lat) * dy) / length
        ).coerceIn(0.0, 1.0)
        val point = a.interpolate(b, fraction)
        return PathProjection(point, fraction, distanceTo(point))
    }

    companion object {
        private const val METERS_PER_DEGREE = 111_195.0
    }
}

internal data class PathProjection(val point: GeoPoint, val fraction: Double, val distance: Double)
internal data class MapBounds(val west: Double, val south: Double, val east: Double, val north: Double)

internal data class CampusGeometry(val kind: String, val points: List<GeoPoint>)

internal data class CampusPlace(
    val id: String,
    val name: String,
    val kind: String,
    val aliases: List<String>,
    val point: GeoPoint?,
    val confidence: String,
    val parentId: String?,
    val region: String?,
    val accessPoint: GeoPoint? = null,
) {
    val isEntrance: Boolean get() = kind == "entrance"

    val hasMappedPosition: Boolean get() = when (confidence) {
        "osm", "verified", "verified-area" -> true
        else -> false
    }
}

internal data class CampusRouteTarget(val point: GeoPoint, val entrance: CampusPlace? = null)

internal data class CampusRoad(
    val from: Int,
    val to: Int,
    val walk: Boolean,
    val bike: Boolean,
)

internal data class CampusMapData(
    val id: CampusId,
    val name: String,
    val bounds: MapBounds,
    val geometry: List<CampusGeometry>,
    val places: List<CampusPlace>,
    val areas: List<List<GeoPoint>>,
    val networkPoints: List<GeoPoint>,
    val networkRoads: List<CampusRoad>,
) {
    fun contains(point: GeoPoint): Boolean = areas.any { it.containsOnMap(point) }

    fun isInsideBuilding(point: GeoPoint): Boolean =
        geometry.any { it.kind == "building" && it.points.containsOnMap(point, edgeTolerance = 0.05) }

    /** Only a unique mapped parent in a unique footprint can supply an indoor origin's exits. */
    fun originEntrances(point: GeoPoint): List<CampusPlace> {
        if (id != CampusId.TDW) return emptyList()
        val footprint = geometry.singleOrNull {
            it.kind == "building" && it.points.containsOnMap(point, edgeTolerance = 0.05)
        } ?: return emptyList()
        val host = places.singleOrNull { place ->
            !place.isEntrance && place.parentId == null && place.hasMappedPosition &&
                place.point?.let { footprint.points.containsOnMap(it, edgeTolerance = 0.05) } == true
        } ?: return emptyList()
        return connectedEntrances(host).filter { entrance ->
            entrance.accessPoint?.let { !isInsideBuilding(it) } == true
        }
    }

    fun mapDestination(place: CampusPlace): CampusPlace =
        destinationEntrance(place) ?: places.firstOrNull { it.isEntrance && it.parentId == place.id } ?: place

    fun destinationEntrance(place: CampusPlace): CampusPlace? = connectedEntrances(place).firstOrNull()

    fun routeTarget(place: CampusPlace): CampusRouteTarget? = routeTargets(place).firstOrNull()

    fun routeTargets(place: CampusPlace): List<CampusRouteTarget> {
        val entrances = connectedEntrances(place).mapNotNull { entrance ->
            entrance.accessPoint?.let { CampusRouteTarget(it, entrance) }
        }
        if (entrances.isNotEmpty() || place.isEntrance) return entrances
        val host = place.parentId?.let { id -> places.firstOrNull { it.id == id } } ?: place
        return listOfNotNull(host.point?.takeIf { host.hasMappedPosition }?.let { CampusRouteTarget(it) })
    }

    private fun connectedEntrances(place: CampusPlace): List<CampusPlace> {
        val host = place.parentId ?: place.id
        val candidates = if (place.isEntrance) listOf(place) else places.filter { it.parentId == host }
        return candidates.filter { entrance ->
            val door = entrance.point
            val access = entrance.accessPoint
            entrance.isEntrance && entrance.confidence == "surveyed" &&
                access != null && door != null && contains(access) && contains(door) &&
                (id != CampusId.TDW || !isInsideBuilding(door))
        }
    }

    fun search(query: String): List<CampusPlace> {
        val term = normalizePlaceName(query)
        if (term.isEmpty()) return emptyList()
        return places.mapNotNull { place ->
            val keys = (listOf(place.name, place.kind, place.region.orEmpty()) + place.aliases)
                .map(::normalizePlaceName)
            val rank = when {
                keys.first() == term -> 0
                term in keys -> 1
                keys.any { it.startsWith(term) } -> 2
                keys.any { it.contains(term) } -> 3
                else -> return@mapNotNull null
            }
            rank to place
        }.sortedBy { it.first }.map { it.second }
    }
}

internal fun normalizePlaceName(name: String): String = name.lowercase().filterNot {
    it.isWhitespace() || it in "·()（）-"
}

/** Resolve the building independently of the room number shown in a course. */
internal fun resolveTimetablePlace(
    raw: String,
    campuses: Collection<CampusMapData>,
): Pair<CampusMapData, CampusPlace>? {
    fun key(text: String): String = normalizePlaceName(Normalizer.normalize(text, Normalizer.Form.NFKC))

    val value = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
    val query = normalizePlaceName(value)
    if (query.isEmpty()) return null
    val sites = campuses.flatMap { campus ->
        campus.places.filterNot(CampusPlace::isEntrance).map { campus to it }
    }

    fun exactMatches(term: String): List<Pair<CampusMapData, CampusPlace>> =
        sites.filter { (_, place) -> key(place.name) == term }.ifEmpty {
            sites.filter { (_, place) -> term.length >= 2 && place.aliases.any { key(it) == term } }
        }

    fun roomMatches(term: String): List<Pair<CampusMapData, CampusPlace>> {
        val matches = sites.mapNotNull { site ->
            val place = site.second
            val prefixLength = (listOf(place.name) + place.aliases)
                .map(::key)
                .filter { it.isNotEmpty() && term.startsWith(it) && roomNumber.matches(term.substring(it.length)) }
                .maxOfOrNull(String::length)
            prefixLength?.let { it to site }
        }
        val longestPrefix = matches.maxOfOrNull { it.first } ?: return emptyList()
        return matches.filter { it.first == longestPrefix }.map { it.second }
    }

    val direct = exactMatches(query).ifEmpty { roomMatches(query) }
    if (direct.isNotEmpty()) return direct.singleOrNull()

    var candidate = value
    val notes = mutableListOf<String>()
    while (true) {
        val note = trailingPlaceNote.find(candidate) ?: return null
        notes.add(note.groupValues[1])
        candidate = candidate.substring(0, note.range.first).trimEnd()
        if (candidate.count { it == '(' } != candidate.count { it == ')' }) return null

        val matches = roomMatches(normalizePlaceName(candidate))
        if (matches.isEmpty()) continue
        val match = matches.singleOrNull() ?: return null
        return match.takeIf { (campus, place) ->
            notes.all { annotation ->
                val term = normalizePlaceName(annotation)
                val campusNames = CampusId.entries.filter { it.title in term }
                val unknownCampus = campusNames.isEmpty() && listOf("校区", "东营", "科教园区").any { it in term }
                val region = listOf("东区", "西区").filter { it == term || campusNames.isNotEmpty() && it in term }
                !unknownCampus && campusNames.all { it == campus.id } &&
                    region.all { it == place.region } &&
                    exactMatches(term).ifEmpty { roomMatches(term) }.all { it == match }
            }
        }
    }
}

private val roomNumber = Regex("[a-z]?[0-9]{2,5}(?:教室|机房|室)?")
private val trailingPlaceNote = Regex("""\(([^()]+)\)\s*$""")

private fun List<GeoPoint>.containsOnMap(point: GeoPoint, edgeTolerance: Double = 0.7): Boolean {
    var inside = false
    for (index in indices) {
        val a = this[index]
        val b = this[(index + 1) % size]
        if (point.projectOnto(a, b).distance <= edgeTolerance) return true
        if ((a.lat > point.lat) != (b.lat > point.lat) &&
            point.lon < (b.lon - a.lon) * (point.lat - a.lat) / (b.lat - a.lat) + a.lon
        ) inside = !inside
    }
    return inside
}

internal object CampusMapDataSource {
    suspend fun load(context: Context): Map<CampusId, CampusMapData> = withContext(Dispatchers.IO) {
        val source = context.assets.open("campus_map.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        parse(source)
    }

    fun parse(source: String): Map<CampusId, CampusMapData> {
        val root = JSONObject(source)
        require(root.getInt("version") == 1)
        val campuses = root.getJSONArray("campuses").objects().map(::parseCampus)
        require(campuses.map { it.id }.toSet() == CampusId.entries.toSet() && campuses.size == CampusId.entries.size)
        return campuses.associateBy { it.id }
    }

    private fun parseCampus(json: JSONObject): CampusMapData {
        val boundsArray = json.getJSONArray("bounds")
        val bounds = MapBounds(boundsArray.getDouble(0), boundsArray.getDouble(1), boundsArray.getDouble(2), boundsArray.getDouble(3))
        require(bounds.west < bounds.east && bounds.south < bounds.north)
        val network = json.getJSONObject("network")
        val nodes = network.getJSONArray("nodes").objects()
        val nodeIndex = nodes.mapIndexed { index, node -> node.getString("id") to index }.toMap()
        require(nodeIndex.size == nodes.size)
        val roads = network.getJSONArray("edges").objects().map { edge ->
            CampusRoad(
                from = nodeIndex.getValue(edge.getString("from")),
                to = nodeIndex.getValue(edge.getString("to")),
                walk = edge.getBoolean("walk"),
                bike = edge.getBoolean("bike"),
            )
        }
        val geometry = json.getJSONArray("geometry").objects().map { shape ->
            CampusGeometry(shape.getString("kind"), shape.getJSONArray("points").points())
        }
        val areas = json.getJSONArray("areas").arrays().map { it.points() }
        val places = json.getJSONArray("places").objects().map(::parsePlace)
        require(places.map { it.id }.distinct().size == places.size && areas.all { it.size >= 3 })
        return CampusMapData(
            id = CampusId.entries.first { it.code == json.getString("id") },
            name = json.getString("name"),
            bounds = bounds,
            geometry = geometry,
            places = places,
            areas = areas,
            networkPoints = nodes.map { it.getJSONArray("point").geoPoint() },
            networkRoads = roads,
        )
    }

    private fun parsePlace(value: JSONObject): CampusPlace = CampusPlace(
        id = value.getString("id"),
        name = value.getString("name"),
        kind = value.getString("kind"),
        aliases = value.getJSONArray("aliases").let { aliases ->
            List(aliases.length()) { aliases.getString(it) }
        },
        point = value.optJSONArray("point")?.geoPoint(),
        confidence = value.getString("confidence"),
        parentId = value.optionalString("parentId"),
        region = value.optionalString("region"),
        accessPoint = value.optJSONArray("accessPoint")?.geoPoint(),
    )

    private fun JSONObject.optionalString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
    private fun JSONArray.arrays(): List<JSONArray> = List(length()) { getJSONArray(it) }
    private fun JSONArray.points(): List<GeoPoint> = arrays().map { it.geoPoint() }

    private fun JSONArray.geoPoint(): GeoPoint {
        val lon = getDouble(0)
        val lat = getDouble(1)
        require(lon in -180.0..180.0 && lat in -90.0..90.0)
        return GeoPoint(lon, lat)
    }
}
