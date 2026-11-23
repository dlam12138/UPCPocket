package com.upcpocket.app.campusmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos

private const val MIN_MAP_ZOOM = 0.85f
private const val MAX_MAP_ZOOM = 18f

private data class MapCamera(val center: Offset, val zoom: Float = 1f)

private class CampusProjection(private val bounds: MapBounds) {
    private val northMeterScale = 111_195.0
    private val eastMeterScale = northMeterScale * cos(Math.toRadians((bounds.north + bounds.south) / 2))
    val extent = Offset(
        ((bounds.east - bounds.west) * eastMeterScale).toFloat(),
        ((bounds.north - bounds.south) * northMeterScale).toFloat(),
    )

    fun project(point: GeoPoint) = Offset(
        ((point.lon - bounds.west) * eastMeterScale).toFloat(),
        ((bounds.north - point.lat) * northMeterScale).toFloat(),
    )

    fun contains(point: GeoPoint): Boolean =
        point.lon in bounds.west..bounds.east && point.lat in bounds.south..bounds.north
}

private class Viewport(
    size: IntSize,
    extent: Offset,
    private val camera: MapCamera,
) {
    private val middle = Offset(size.width / 2f, size.height / 2f)
    val fitScale = minOf(size.width / extent.x, size.height / extent.y).coerceAtLeast(0.001f) * 0.92f
    val scale = camera.zoom * fitScale
    val origin = middle - camera.center * scale

    fun screen(world: Offset): Offset = origin + world * scale

    fun transformAround(anchor: Offset, pan: Offset, zoomChange: Float): MapCamera {
        val zoom = (camera.zoom * zoomChange).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
        val worldAtAnchor = (anchor - origin) / scale
        return MapCamera(worldAtAnchor - (anchor + pan - middle) / (zoom * fitScale), zoom)
    }
}

private data class MapLayer(val kind: String, val paths: List<Path>)
private data class ProjectedPlace(val place: CampusPlace, val point: Offset)
private data class RoutePaths(val primary: Path? = null, val walk: Path? = null)

private data class RouteProgressPaths(val traveled: RoutePaths, val remaining: RoutePaths)

private data class ProjectedRoute(
    val route: CampusRoute,
    val points: List<Offset>,
    val walkingContinuation: Boolean,
) {
    val paths: RoutePaths = route.walkFromIndex?.let { split ->
        RoutePaths(mapPath(points.subList(0, split + 1)), mapPath(points.subList(split, points.size)))
    } ?: singlePath(mapPath(points))

    private fun singlePath(path: Path): RoutePaths =
        if (walkingContinuation) RoutePaths(walk = path) else RoutePaths(primary = path)

    fun progressPaths(alongMeters: Double): RouteProgressPaths? {
        val position = route.positionAt(alongMeters) ?: return null
        val segment = position.segmentIndex
        val a = points[segment]
        val at = a + (points[segment + 1] - a) * position.fraction.toFloat()
        fun before(first: Int): Path = mapPath(points.subList(first, segment + 1) + at)
        fun after(last: Int): Path = mapPath(listOf(at) + points.subList(segment + 1, last + 1))

        val split = route.walkFromIndex
        if (split == null) {
            val traveled = if (walkingContinuation) singlePath(before(0)) else paths
            return RouteProgressPaths(traveled, singlePath(after(points.lastIndex)))
        }
        return if (segment < split) {
            RouteProgressPaths(
                traveled = RoutePaths(primary = before(0)),
                remaining = RoutePaths(after(split), paths.walk),
            )
        } else {
            RouteProgressPaths(
                traveled = RoutePaths(paths.primary, before(split)),
                remaining = RoutePaths(walk = after(points.lastIndex)),
            )
        }
    }
}

private fun mapPath(points: List<Offset>, closed: Boolean = false) = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    for (index in 1 until points.size) lineTo(points[index].x, points[index].y)
    if (closed && points.isNotEmpty()) close()
}

private val filledLayers = setOf("area", "building", "water", "field")
private val layerOrder = listOf("water", "area", "field", "path", "road", "building")
private val majorMapPlaces = setOf(
    "south", "library", "new-library", "sport", "north-gate", "south-gate", "engineering",
    "teach", "learning", "west-food", "ling", "east-sport",
)

@Composable
internal fun CampusMapCanvas(
    campus: CampusMapData,
    state: CampusMapState,
    focusPlace: CampusPlace?,
    focusRequest: Int,
    following: Boolean,
    modifier: Modifier = Modifier,
    onGesture: () -> Unit,
    onLocate: () -> Unit,
    onPlace: (CampusPlace) -> Unit,
    onRoute: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.red + colors.surface.green + colors.surface.blue < 1.5f
    val palette = remember(dark, colors.surface, colors.onSurface) {
        CampusMapColors(dark, colors.surface, colors.onSurface)
    }
    val projection = remember(campus.bounds) { CampusProjection(campus.bounds) }
    val heading = rememberCampusHeading(campus.bounds)
    val extent = projection.extent
    val center = extent / 2f
    val paths = remember(campus.geometry, projection) {
        val grouped = campus.geometry.groupBy { it.kind }
        layerOrder.map { kind ->
            MapLayer(kind, grouped[kind].orEmpty().map { shape ->
                mapPath(shape.points.map(projection::project), closed = kind in filledLayers)
            })
        }
    }
    val places = remember(campus.places, projection) {
        campus.places.mapNotNull { place ->
            place.point?.takeIf { place.parentId == null && !place.isEntrance }
                ?.let { ProjectedPlace(place, projection.project(it)) }
        }
    }
    val routes = remember(state.routes, state.mode, projection) {
        state.routes.map { route ->
            ProjectedRoute(
                route,
                route.points.map(projection::project),
                walkingContinuation = state.mode == TravelMode.BIKE && route.mode == TravelMode.WALK,
            )
        }
    }
    val selectedRoute = routes.find { it.route.id == state.selectedRouteId }
    val progress = state.routeProgressMeters
    val progressPaths = remember(selectedRoute, progress) {
        progress?.let { selectedRoute?.progressPaths(it) }
    }
    val navigates = isGuidanceVisible(state)
    val destination = state.destination?.takeUnless(CampusPlace::isEntrance)
    val previewPlace = remember(campus, destination) {
        destination?.let(campus::mapDestination)
    }
    val endPoint = selectedRoute?.route?.points?.lastOrNull() ?: previewPlace?.point
    val destinationPoint = selectedRoute?.points?.lastOrNull() ?: previewPlace?.point?.let(projection::project)
    val sportsEntrance = if (destination?.kind == "sport") {
        if (selectedRoute == null) previewPlace?.takeIf(CampusPlace::isEntrance)
        else selectedRoute.route.target?.entrance
    } else null
    val destinationLabel = when {
        sportsEntrance == null -> destination?.name
        sportsEntrance.point == endPoint -> sportsEntrance.name
        selectedRoute?.route?.mode == TravelMode.BIKE -> "骑行终点"
        else -> "路线终点"
    }
    val manualStart = state.manualStart
    val selectedMarker = destination?.let { it.parentId ?: it.id }
    val hiddenPlaceMarker = selectedMarker.takeUnless { sportsEntrance != null && destination?.point != endPoint }
    val textMeasure = rememberTextMeasurer(cacheSize = 60)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var camera by remember(projection) { mutableStateOf(MapCamera(center)) }
    val routeZoom by remember(projection) { derivedStateOf { camera.zoom } }
    val density = LocalDensity.current
    val usesWalkingDashes = routes.any { it.paths.walk != null }
    val walkingDashes = remember(usesWalkingDashes, routeZoom, canvasSize, extent, density) {
        if (!usesWalkingDashes || canvasSize == IntSize.Zero) null else with(density) {
            val scale = Viewport(canvasSize, extent, MapCamera(Offset.Zero, routeZoom)).scale
            PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx() / scale, 6.dp.toPx() / scale))
        }
    }
    val activePlaceHandler by rememberUpdatedState(onPlace)
    val activeRouteHandler by rememberUpdatedState(onRoute)
    val activeGestureHandler by rememberUpdatedState(onGesture)
    val currentRoutes by rememberUpdatedState(routes)
    val currentNavigates by rememberUpdatedState(navigates)
    val currentSelectedMarker by rememberUpdatedState(selectedMarker)
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(focusRequest, projection) {
        val point = focusPlace?.let(campus::mapDestination)?.point ?: return@LaunchedEffect
        camera = MapCamera(projection.project(point), maxOf(2.5f, camera.zoom))
    }
    LaunchedEffect(selectedRoute?.route?.id, canvasSize, projection) {
        if (canvasSize == IntSize.Zero || following || navigates) return@LaunchedEffect
        val line = selectedRoute?.points.orEmpty()
        if (line.isEmpty()) return@LaunchedEffect
        val bounds = Rect(
            left = line.minOf { it.x }, top = line.minOf { it.y },
            right = line.maxOf { it.x }, bottom = line.maxOf { it.y },
        )
        val viewport = Viewport(canvasSize, extent, camera)
        val pixelRatio = minOf(
            canvasSize.width * 0.77f / bounds.width.coerceAtLeast(34f),
            canvasSize.height * 0.72f / bounds.height.coerceAtLeast(34f),
        )
        camera = MapCamera(bounds.center, (pixelRatio / viewport.fitScale).coerceIn(MIN_MAP_ZOOM, 9f))
    }
    LaunchedEffect(following, state.fix?.point, navigates, projection) {
        val point = state.fix?.point ?: return@LaunchedEffect
        if (following && projection.contains(point)) {
            camera = MapCamera(projection.project(point), maxOf(camera.zoom, if (navigates) 4f else 1.7f))
        }
    }
    fun changeZoom(factor: Float) {
        activeGestureHandler()
        camera = camera.copy(zoom = (camera.zoom * factor).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM))
    }

    Box(modifier.background(palette.ground).clipToBounds()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            if (canvasSize != IntSize.Zero) drawBasemap(paths, Viewport(canvasSize, extent, camera), palette)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "${campus.name}地图，可拖动或双指缩放" }
                .onSizeChanged { canvasSize = it }
                .pointerInput(projection) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        activeGestureHandler()
                        val adjusted = Viewport(canvasSize, extent, camera).transformAround(centroid, pan, zoom)
                        camera = adjusted.copy(
                            center = Offset(
                                adjusted.center.x.coerceIn(-extent.x * .25f, extent.x * 1.25f),
                                adjusted.center.y.coerceIn(-extent.y * .25f, extent.y * 1.25f),
                            ),
                        )
                    }
                }
                .pointerInput(projection) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            activeGestureHandler()
                            camera = Viewport(canvasSize, extent, camera).transformAround(tap, Offset.Zero, 1.55f)
                        },
                        onTap = { tap ->
                            if (currentNavigates) return@detectTapGestures
                            val view = Viewport(canvasSize, extent, camera)
                            val hit = places.asSequence()
                                .map { place -> place to (view.screen(place.point) - tap).getDistance() }
                                .filter { (_, distance) -> distance <= 23.dp.toPx() }
                                .minByOrNull { (place, distance) ->
                                    distance - if (place.place.id == currentSelectedMarker) 2.dp.toPx() else 0f
                                }
                            if (hit != null && hit.second < 13.dp.toPx()) {
                                activePlaceHandler(hit.first.place)
                            } else {
                                val line = currentRoutes.minByOrNull { line ->
                                    line.distanceFrom(tap, view)
                                }
                                if (line != null && line.distanceFrom(tap, view) <= 13.dp.toPx()) {
                                    activeRouteHandler(line.route.id)
                                } else if (hit != null) {
                                    activePlaceHandler(hit.first.place)
                                }
                            }
                        },
                    )
                },
        ) {
            if (canvasSize == IntSize.Zero) return@Canvas
            val view = Viewport(canvasSize, extent, camera)
            withTransform({
                translate(view.origin.x, view.origin.y)
                scale(view.scale, view.scale, Offset.Zero)
            }) {
                if (!navigates) {
                    for (line in routes) {
                        if (line.route.id == state.selectedRouteId) continue
                        drawRoutePaths(
                            line.paths, palette.alternative, palette.walk.copy(alpha = 0.65f),
                            4.dp.toPx() / view.scale, walkingDashes,
                        )
                    }
                }
                selectedRoute?.let { line ->
                    val routeWidth = 6.dp.toPx() / view.scale
                    if (progressPaths != null) {
                        drawRoutePaths(
                            progressPaths.traveled, palette.traveled, palette.walk.copy(alpha = 0.28f),
                            routeWidth, walkingDashes,
                        )
                    }
                    val highlighted = progressPaths?.remaining ?: line.paths
                    highlighted.primary?.let { path ->
                        drawPath(path, palette.routeOutline, style = Stroke(9.dp.toPx() / view.scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    drawRoutePaths(highlighted, palette.route, palette.walk, routeWidth, walkingDashes)
                }
            }

            val bounds = Rect(Offset.Zero, size)
            val occupied = mutableListOf<Rect>()
            val visiblePlaces = if (navigates) emptyList() else places
            for (item in visiblePlaces) {
                if (item.place.id == hiddenPlaceMarker) continue
                val point = view.screen(item.point)
                if (!bounds.contains(point)) continue
                val radius = if (camera.zoom >= 2.5f) 4.2.dp.toPx() else 3.2.dp.toPx()
                val verified = item.place.hasMappedPosition || item.place.confidence == "surveyed"
                val pin = if (verified) palette.poi else palette.estimated
                drawCircle(if (verified) pin else palette.ground, radius, point)
                drawCircle(pin, radius, point, style = Stroke(1.0.dp.toPx()))
            }
            fun drawPlaceLabel(value: String, centerPoint: Offset, emphasized: Boolean) {
                val layout = textMeasure.measure(
                    value,
                    TextStyle(
                        color = palette.text,
                        fontSize = if (emphasized) 12.sp else 10.sp,
                        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                val label = Size(layout.size.width.toFloat(), layout.size.height.toFloat())
                val space = 3.dp.toPx()
                if (label.width + 2 * space > size.width) return
                val x = (centerPoint.x - label.width / 2).coerceIn(space, size.width - label.width - space)
                val y = if (centerPoint.y > label.height + 16.dp.toPx()) {
                    centerPoint.y - label.height - 12.dp.toPx()
                } else {
                    centerPoint.y + 12.dp.toPx()
                }
                val rect = Rect(x - space, y - space, x + label.width + space, y + label.height + space)
                if (rect.bottom > size.height || occupied.any { it.overlaps(rect) }) return
                occupied += rect
                drawRoundRect(palette.label, rect.topLeft, rect.size, CornerRadius(5.dp.toPx()))
                drawText(layout, topLeft = Offset(x, y))
            }
            if (destinationLabel != null) {
                destinationPoint?.let(view::screen)?.takeIf(bounds::contains)?.let { end ->
                    drawCircle(palette.label, 10.dp.toPx(), end)
                    drawCircle(palette.destination, 6.5.dp.toPx(), end)
                    drawPlaceLabel(destinationLabel, end, emphasized = true)
                }
            }
            selectedRoute?.let { line ->
                val split = line.route.walkFromIndex ?: return@let
                if (progress != null && line.route.modeAt(progress) == TravelMode.WALK) return@let
                val marker = view.screen(line.points[split])
                if (bounds.contains(marker)) {
                    drawCircle(palette.label, 8.dp.toPx(), marker)
                    drawCircle(palette.walk, 4.5.dp.toPx(), marker)
                    drawPlaceLabel("下车步行", marker, emphasized = true)
                }
            }
            val routeStart = if (progressPaths == null) {
                selectedRoute?.points?.firstOrNull() ?: manualStart?.point?.let(projection::project)
            } else null
            routeStart?.let(view::screen)?.takeIf(bounds::contains)?.let { start ->
                drawCircle(palette.label, 9.dp.toPx(), start)
                drawCircle(palette.start, 5.5.dp.toPx(), start)
            }
            if (!navigates) {
                visiblePlaces.asSequence()
                    .filter { it.place.id != hiddenPlaceMarker && (it.place.id in majorMapPlaces || camera.zoom >= 4f) }
                    .map { item -> item.place.name to view.screen(item.point) }
                    .filter { (_, point) -> bounds.contains(point) }
                    .take(if (camera.zoom >= 4f) 44 else 14)
                    .forEach { (name, point) ->
                        drawPlaceLabel(name, point, emphasized = false)
                    }
            }
        }
        state.fix?.takeIf { projection.contains(it.point) }?.let { fix ->
            val point = projection.project(fix.point)
            Box(
                Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                    val arrow = Path().apply {
                        moveTo(0f, -7.dp.toPx())
                        lineTo(5.5.dp.toPx(), 5.5.dp.toPx())
                        lineTo(0f, 3.dp.toPx())
                        lineTo(-5.5.dp.toPx(), 5.5.dp.toPx())
                        close()
                    }
                    val uncertaintyColor = palette.position.copy(alpha = 0.12f)
                    onDrawBehind {
                        if (canvasSize == IntSize.Zero) return@onDrawBehind
                        val view = Viewport(canvasSize, extent, camera)
                        val at = view.screen(point)
                        val uncertainty = (fix.accuracy.toFloat() * view.scale).coerceAtMost(size.maxDimension * 3f)
                        drawCircle(uncertaintyColor, uncertainty, at)
                        drawCircle(palette.label, 10.dp.toPx(), at)
                        val azimuth = if (navigates) fix.bearing ?: heading.value else heading.value ?: fix.bearing
                        if (azimuth == null) {
                            drawCircle(palette.position, 7.dp.toPx(), at)
                        } else {
                            withTransform({
                                translate(at.x, at.y)
                                rotate(azimuth, pivot = Offset.Zero)
                            }) { drawPath(arrow, palette.position) }
                        }
                    }
                },
            )
        }
        Surface(
            color = colors.surface.copy(alpha = 0.93f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp),
        ) {
            CampusCompassIndicator(heading)
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 13.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FilledTonalIconButton(onClick = { changeZoom(1.5f) }, enabled = camera.zoom < MAX_MAP_ZOOM) {
                Icon(Icons.Default.Add, contentDescription = "放大地图")
            }
            FilledTonalIconButton(onClick = { changeZoom(1f / 1.5f) }, enabled = camera.zoom > MIN_MAP_ZOOM) {
                Icon(Icons.Default.Remove, contentDescription = "缩小地图")
            }
            FilledTonalIconButton(onClick = onLocate) {
                Icon(Icons.Default.MyLocation, contentDescription = "定位并回到当前位置")
            }
        }
        Surface(
            color = colors.surface.copy(alpha = 0.91f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(9.dp),
        ) {
            TextButton(onClick = {
                try {
                    uriHandler.openUri("https://www.openstreetmap.org/copyright")
                } catch (_: IllegalArgumentException) {
                    // Opening the source requires an installed browser.
                }
            }) {
                Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun DrawScope.drawRoutePaths(
    paths: RoutePaths,
    primary: Color,
    walk: Color,
    width: Float,
    walkingDashes: PathEffect?,
) {
    paths.primary?.let { drawPath(it, primary, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    paths.walk?.let { drawPath(it, walk, style = Stroke(width, join = StrokeJoin.Round, pathEffect = walkingDashes)) }
}

private fun DrawScope.drawBasemap(layers: List<MapLayer>, view: Viewport, palette: CampusMapColors) {
    withTransform({
        translate(view.origin.x, view.origin.y)
        scale(view.scale, view.scale, Offset.Zero)
    }) {
        for (layer in layers) {
            for (path in layer.paths) {
                when (layer.kind) {
                    "road" -> {
                        drawPath(path, palette.roadEdge, style = Stroke(4.5.dp.toPx() / view.scale, cap = StrokeCap.Round))
                        drawPath(path, palette.road, style = Stroke(3.dp.toPx() / view.scale, cap = StrokeCap.Round))
                    }
                    "path" -> drawPath(path, palette.path, style = Stroke(1.35.dp.toPx() / view.scale, cap = StrokeCap.Round))
                    "building" -> {
                        drawPath(path, palette.building)
                        drawPath(path, palette.buildingEdge, style = Stroke(0.55.dp.toPx() / view.scale))
                    }
                    "water" -> drawPath(path, palette.water)
                    "field" -> drawPath(path, palette.field)
                    "area" -> drawPath(path, palette.campus)
                }
            }
        }
    }
}

private fun ProjectedRoute.distanceFrom(point: Offset, view: Viewport): Float {
    var minimum = Float.POSITIVE_INFINITY
    for (index in 0 until points.lastIndex) {
        val start = view.screen(points[index])
        val end = view.screen(points[index + 1])
        minimum = minOf(minimum, distanceFromScreenSegment(point, start, end))
    }
    return minimum
}

private fun distanceFromScreenSegment(point: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val squared = ab.x * ab.x + ab.y * ab.y
    if (squared <= 0.0001f) return (point - a).getDistance()
    val ap = point - a
    val t = ((ap.x * ab.x + ap.y * ab.y) / squared).coerceIn(0f, 1f)
    return (point - (a + ab * t)).getDistance()
}

private class CampusMapColors(dark: Boolean, val label: Color, val text: Color) {
    val ground = if (dark) Color(0xFF202B29) else Color(0xFFEDF2EB)
    val campus = if (dark) Color(0xFF273830) else Color(0xFFE2EDDE)
    val water = if (dark) Color(0xFF214553) else Color(0xFFB9DDEE)
    val field = if (dark) Color(0xFF335047) else Color(0xFFCEE4CB)
    val building = if (dark) Color(0xFF3D5249) else Color(0xFFCAD6C8)
    val buildingEdge = if (dark) Color(0xFF53675E) else Color(0xFFA9B9AF)
    val path = if (dark) Color(0xFF475B52) else Color(0xFFD3DCD0)
    val road = if (dark) Color(0xFF65736C) else Color(0xFFFAFBF7)
    val roadEdge = if (dark) Color(0xFF192521) else Color(0xFFCCD6CB)
    val poi = if (dark) Color(0xFFB4D0FC) else Color(0xFF4068A6)
    val estimated = if (dark) Color(0xFFA7BBAE) else Color(0xFF647E6E)
    val alternative = if (dark) Color(0xFF829BC6) else Color(0xFF86A2CA)
    val routeOutline = if (dark) Color(0xFF243C6A) else Color(0xFFDAE8FF)
    val route = if (dark) Color(0xFF7EABFF) else Color(0xFF2464D4)
    val walk = if (dark) Color(0xFFFFB46A) else Color(0xFFB95000)
    val traveled = route.copy(alpha = 0.28f)
    val position = if (dark) Color(0xFF74B8FF) else Color(0xFF096DE3)
    val start = if (dark) Color(0xFF85D7A8) else Color(0xFF1B8752)
    val destination = if (dark) Color(0xFFFF9B98) else Color(0xFFB84554)
}
