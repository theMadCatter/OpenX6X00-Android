// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// QRZ lookup on the log screen: name, QTH, grid, photo, distance and bearing from the
// station grid, and worked-before from the local log. The map is a canvas. No tile
// server and no API key. A limited QRZ record still shows the fields that arrived.

package radio.kd0cq.x6100.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import radio.kd0cq.x6100.log.Geo
import radio.kd0cq.x6100.log.LatLon
import radio.kd0cq.x6100.log.Maidenhead
import radio.kd0cq.x6100.log.Qso
import radio.kd0cq.x6100.log.QsoBook
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.theme.RfGuru

@Composable
fun QrzSection(
    viewModel: RadioViewModel,
    call: String,
    onCall: (String) -> Unit,
    onName: (String) -> Unit,
    onQth: (String) -> Unit,
    onGrid: (String) -> Unit,
) {
    val result by viewModel.qrzResult.collectAsState()
    val busy by viewModel.qrzBusy.collectAsState()
    val qsos by viewModel.qsos.collectAsState()
    val state by viewModel.state.collectAsState()
    var user by remember { mutableStateOf(viewModel.qrzUsername()) }
    var password by remember { mutableStateOf("") }
    var homeGrid by remember { mutableStateOf(viewModel.stationPlace.grid()) }
    val context = LocalContext.current
    val found = result?.call
    val home = Maidenhead.decode(homeGrid)
    val dx = found?.let { spot ->
        if (spot.lat != null && spot.lon != null) LatLon(spot.lat, spot.lon) else Maidenhead.decode(spot.grid)
    }

    Text("QRZ", color = RfGuru.gold, fontFamily = FontFamily.Monospace)
    Text(
        "Username and password stay in the keystore. They are not written into the log.",
        color = RfGuru.dim,
        fontFamily = FontFamily.Monospace,
    )
    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("QRZ username") }, singleLine = true)
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("QRZ password") },
        singleLine = true,
    )
    OutlinedButton(onClick = {
        viewModel.saveQrzCredentials(user, password)
        password = ""
    }) { Text("Save QRZ login") }
    OutlinedTextField(
        value = homeGrid,
        onValueChange = { homeGrid = it.uppercase() },
        label = { Text("My grid") },
        singleLine = true,
    )
    OutlinedButton(onClick = { viewModel.stationPlace.writeGrid(homeGrid) }) { Text("Save my grid") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.lookupQrz(call) }, enabled = call.isNotBlank() && !busy) {
            Text(if (busy) "Looking up" else "Look up")
        }
        OutlinedButton(onClick = {
            val text = viewModel.adifExport()
            val dir = File(context.cacheDir, "adif").apply { mkdirs() }
            val file = File(dir, "openx6x00.adi")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "ADIF"))
        }) { Text("Share ADIF") }
    }
    state?.let {
        Text(
            "From the radio: ${it.modeLabel}  ${radio.kd0cq.x6100.ui.face.RadioFace.formatFrequency(it.freqHz)}",
            color = RfGuru.dim,
            fontFamily = FontFamily.Monospace,
        )
    }
    result?.error?.let { Text(it, color = RfGuru.red, fontFamily = FontFamily.Monospace) }
    if (result?.limited == true) {
        Text(
            "QRZ did not include the full record. What is below is the part that came back.",
            color = RfGuru.dim,
            fontFamily = FontFamily.Monospace,
        )
    }
    found?.let { spot ->
        LaunchedEffect(spot.call, spot.name, spot.qth, spot.grid) {
            if (spot.call.isNotBlank()) onCall(spot.call)
            if (spot.name.isNotBlank()) onName(spot.name)
            if (spot.qth.isNotBlank()) onQth(spot.qth)
            if (spot.grid.isNotBlank()) onGrid(spot.grid)
        }
        Text("${spot.name.ifBlank { spot.call }}  ${spot.qth}", color = RfGuru.text, fontFamily = FontFamily.Monospace)
        Text(spot.grid.ifBlank { "no grid" }, color = RfGuru.goldText, fontFamily = FontFamily.Monospace)
        if (QsoBook.workedBefore(spot.call, qsos)) {
            Text("Worked before", color = RfGuru.gold, fontFamily = FontFamily.Monospace)
        }
        if (home != null && dx != null) {
            val km = Geo.distanceKm(home, dx)
            val bearing = Geo.bearingDeg(home, dx)
            Text(
                "${Geo.distanceText(km)}   ${Geo.bearingText(bearing)}",
                color = RfGuru.text,
                fontFamily = FontFamily.Monospace,
            )
        }
        QrzPhoto(spot.imageUrl)
        StationMap(home, dx)
    }
}

@Composable
private fun QrzPhoto(url: String?) {
    if (url.isNullOrBlank()) return
    var bitmap by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }.getOrNull()
        }
        bitmap = loaded
    }
    bitmap?.let {
        androidx.compose.foundation.Image(
            bitmap = it,
            contentDescription = "QRZ photo",
            modifier = Modifier.size(96.dp),
        )
    }
}

/** Equirectangular canvas. Home is gold, the station is blue. No tiles. */
@Composable
private fun StationMap(home: LatLon?, dx: LatLon?) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(RfGuru.bar)
            .padding(4.dp),
    ) {
        fun x(lon: Double) = ((lon + 180.0) / 360.0 * size.width).toFloat()
        fun y(lat: Double) = ((90.0 - lat) / 180.0 * size.height).toFloat()
        drawLine(RfGuru.border, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f)
        drawLine(RfGuru.border, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1f)
        home?.let { drawCircle(RfGuru.gold, 6f, Offset(x(it.lon), y(it.lat))) }
        dx?.let { drawCircle(RfGuru.blue, 6f, Offset(x(it.lon), y(it.lat))) }
        if (home != null && dx != null) {
            drawLine(RfGuru.goldText, Offset(x(home.lon), y(home.lat)), Offset(x(dx.lon), y(dx.lat)), 2f)
        }
    }
}
