package pl.example.trasa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun MapTab(vm: RouteVM) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
            controller.setZoom(12.0); controller.setCenter(GeoPoint(53.13, 23.16))
        }
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    LaunchedEffect(vm.order, vm.options) { if (!vm.navActive) vm.loadPath() }
    Column(Modifier.fillMaxSize()) {
        Text("Przytrzymaj mapę, aby dodać przystanek.", style = MaterialTheme.typography.labelSmall)
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize(), update = { redraw(it, vm, ctx) })
    }
}

private fun numberIcon(ctx: Context, n: String, color: Int): BitmapDrawable {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = color
    c.drawCircle(s / 2f, s / 2f, s / 2f - 2, p)
    p.color = android.graphics.Color.WHITE; p.textSize = 34f; p.textAlign = Paint.Align.CENTER
    c.drawText(n, s / 2f, s / 2f + 12f, p)
    return BitmapDrawable(ctx.resources, bmp)
}

private fun redraw(map: MapView, vm: RouteVM, ctx: Context) {
    map.overlays.clear()
    map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
        override fun longPressHelper(p: GeoPoint?): Boolean {
            if (p != null) vm.addStopAt(LatLng(p.latitude, p.longitude))
            return true
        }
    }))
    val ord = vm.order.filter { it != vm.dummyIdx }
    val l = vm.leg
    val line = if (vm.navActive && l != null) l.points else vm.mapPath
    if (line.size > 1) map.overlays.add(Polyline().apply {
        setPoints(line.map { GeoPoint(it.lat, it.lng) })
        outlinePaint.color = android.graphics.Color.rgb(25, 118, 210); outlinePaint.strokeWidth = 10f
    })
    ord.forEachIndexed { k, g ->
        val c = vm.coords[g]
        val col = when (k) { 0 -> android.graphics.Color.rgb(46, 125, 50); ord.lastIndex -> android.graphics.Color.rgb(198, 40, 40); else -> android.graphics.Color.rgb(25, 118, 210) }
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(c.lat, c.lng); title = "${k + 1}. ${vm.points[g]}"
            icon = numberIcon(ctx, "${k + 1}", col); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        })
    }
    vm.carPos?.let { c ->
        if (vm.navActive) map.overlays.add(Marker(map).apply {
            position = GeoPoint(c.lat, c.lng); title = "Ty"
            icon = numberIcon(ctx, "▲", android.graphics.Color.rgb(255, 143, 0)); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        })
    }
    val key = ord.hashCode()
    if (ord.isNotEmpty() && map.tag != key) {          // dopasuj widok tylko po zmianie trasy (nie przy każdym przesunięciu)
        map.tag = key
        map.post {
            val pts = ord.map { GeoPoint(vm.coords[it].lat, vm.coords[it].lng) }
            if (pts.size == 1) { map.controller.setZoom(15.0); map.controller.setCenter(pts[0]) }
            else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 100)
        }
    }
    map.invalidate()
}
