package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.launcher.PermAccess.Sensor
import com.diegonmarcos.superapp.launcher.PermAccess.SensorState
import com.diegonmarcos.superapp.ui.KitBubble
import com.diegonmarcos.superapp.ui.KitPopup
import kotlin.math.roundToInt

/**
 * #1673 "Permission Access Status": the row of Mic / Camera / Location icons directly under the resources line of the home status
 * block (right cluster), and the 24 h privacy dashboard its tap opens. Live states come from [PermAccessWatch] (callbacks, only while
 * the launcher is visible); the dashboard reads the app-ops history once when it opens, through the SuperApp's shell broker.
 */
object PermAccessUi {

    /** Coloured while in use, like Android's privacy indicators (green for Mic / Camera, blue for Location). */
    fun tint(s: Sensor, state: SensorState): Color = when (state) {
        SensorState.ACTIVE -> if (s == Sensor.LOCATION) Color(0xFF60A5FA) else Color(0xFF34D399)
        SensorState.IDLE -> Color(0x99FFFFFF)
        SensorState.UNKNOWN -> Color(0x55FFFFFF)
    }

    fun spoken(s: Sensor, state: SensorState, users: Set<String>): String = s.label + ", " + when (state) {
        SensorState.ACTIVE -> "in use" + if (users.isEmpty()) "" else " by " + users.joinToString(", ")
        SensorState.IDLE -> "not in use"
        SensorState.UNKNOWN -> "state unknown"
    }

    private fun vector(name: String, d: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.White)).build()

    // Material Design icons (Apache 2.0): mic, videocam, location_on.
    val MIC = vector("mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    val CAMERA = vector("camera", "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z")
    val LOCATION = vector("location", "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z")
    fun icon(s: Sensor): ImageVector = when (s) { Sensor.MIC -> MIC; Sensor.CAMERA -> CAMERA; Sensor.LOCATION -> LOCATION }

    /** The broker's reading, or null when no ADB shell is armed / it refused (then the dashboard says why). Blocking. */
    fun brokerRead(ctx: Context): (String) -> String? = { cmd ->
        runCatching {
            val b = com.diegonmarcos.superapp.shellbroker.ShellBroker
            if (!com.diegonmarcos.superapp.system.AdbReadiness.check(ctx).usable) null else b.execText(ctx, cmd)
        }.getOrNull()
    }

    /** The dashboard read: [PermAccess.DASHBOARD_CMD] through [read], parsed for the 24 h window. Blocking. */
    fun load(read: (String) -> String?, nowMs: Long = System.currentTimeMillis()): PermAccess.Dashboard {
        val text = runCatching { read(PermAccess.DASHBOARD_CMD) }.getOrNull()
            ?: return PermAccess.Dashboard.NoAccess("No ADB shell is armed for the SuperApp, or it refused the app-ops read.")
        if (!text.contains("Package ") && !text.contains("Uid ")) return PermAccess.Dashboard.NoAccess(
            "The system answered without app-ops data: ${text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "empty output"}")
        return PermAccess.dashboard(PermAccess.parseDumpsys(text, nowMs), nowMs)
    }

    fun show(ctx: Context, host: View, rect: IntRect, read: (String) -> String? = brokerRead(ctx)) {
        KitPopup.show(host, rect, KitPopup.Align.END) { close -> DashboardSheet(ctx, read, onPerms = { close(); openPerms(ctx) }) }
    }

    fun openPerms(ctx: Context) {
        runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(ctx, "Privacy dashboard", emptySet()) }
    }
}

/** The row under the resources line: Mic, Camera, Location. A tap opens the 24 h dashboard. */
@Composable
internal fun PermAccessStatusRow(watch: PermAccessWatch, onOpen: (IntRect) -> Unit) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        Modifier.testTag("permaccess:row").onGloballyPositioned { coords = it }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                val c = coords?.takeIf { it.isAttached } ?: return@clickable
                val r = c.boundsInWindow()
                onOpen(IntRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
            }
            .padding(horizontal = 3.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in Sensor.values()) {
            val st = watch.state(s)
            val users = watch.users[s].orEmpty()
            Box(Modifier.testTag("permaccess:${s.name.lowercase()}:${st.name.lowercase()}")
                .semantics { contentDescription = PermAccessUi.spoken(s, st, users) }) {
                Icon(PermAccessUi.icon(s), contentDescription = null, tint = PermAccessUi.tint(s, st), modifier = Modifier.size(10.dp))
                if (st == SensorState.UNKNOWN) Text("?", color = Color(0xAAFFFFFF), fontSize = 6.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(start = 7.dp))
            }
        }
    }
}

/** The 24 h privacy dashboard: one row per group Android reports, apps' icons + "+N", counts / last time, expandable. */
@Composable
internal fun DashboardSheet(ctx: Context, read: (String) -> String?, onPerms: () -> Unit, initial: PermAccess.Dashboard? = null) {
    var dash by remember { mutableStateOf(initial) }
    LaunchedEffect(Unit) {
        if (dash == null) dash = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PermAccessUi.load(read) }
    }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    KitBubble(Modifier.heightIn(max = maxH).widthIn(max = 360.dp).testTag("permaccess:dashboard"), scroll = rememberScrollState()) {
        Text("Privacy dashboard · past 24 hours", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        when (val d = dash) {
            null -> Text("Reading the system's permission history...", color = DIM, fontSize = 11.sp, modifier = Modifier.testTag("permaccess:loading"))
            is PermAccess.Dashboard.NoAccess -> NoAccessBlock(d.reason, onPerms)
            is PermAccess.Dashboard.Ready -> {
                if (d.empty) Text("No app used a permission in the past 24 hours.", color = DIM, fontSize = 11.sp, modifier = Modifier.testTag("permaccess:empty"))
                for (row in d.rows) GroupRow(ctx, row)
                Text("From the system's app-ops records (last access per app and state), read ${PermAccess.clock(d.readAtMs)}.",
                    color = DIM, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private val DIM = Color(0xAAFFFFFF)

@Composable
private fun NoAccessBlock(reason: String, onPerms: () -> Unit) {
    Column(Modifier.testTag("permaccess:noaccess").padding(top = 4.dp)) {
        Text("Permission history unavailable", color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(reason, color = Color.White, fontSize = 11.sp)
        Text(PermAccess.NO_ACCESS_HELP, color = DIM, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        for (g in PermAccess.GROUPS) Text("${g.label}: unknown", color = Color(0x55FFFFFF), fontSize = 10.sp)
        Text("Open Configs > Perms", color = Color(0xFF60A5FA), fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("permaccess:openperms").padding(top = 6.dp)
                .background(Color(0x2260A5FA), RoundedCornerShape(6.dp)).clickable { onPerms() }.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun GroupRow(ctx: Context, row: PermAccess.Row) {
    var open by remember(row.group.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("permaccess:group:${row.group.id}").alpha(if (row.used) 1f else 0.45f)
        .clickable(enabled = row.used) { open = !open }.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.group.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            for (u in row.shown) AppIcon(ctx, u.pkg, 16)
            if (row.overflow > 0) Text("+${row.overflow}", color = DIM, fontSize = 10.sp, modifier = Modifier.testTag("permaccess:overflow:${row.group.id}").padding(start = 2.dp))
        }
        Text(PermAccess.summary(row), color = DIM, fontSize = 10.sp)
        if (open) for (u in row.apps) Row(Modifier.padding(start = 8.dp, top = 2.dp).testTag("permaccess:app:${row.group.id}:${u.pkg}"), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(ctx, u.pkg, 14)
            Text(" ${label(ctx, u.pkg)}", color = Color.White, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text((if (u.running) "now · " else "") + "${u.count}x · ${PermAccess.clock(u.lastMs)}", color = DIM, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun label(ctx: Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** Through the one icon cache (test-home-avatar-icon-cache.sh): off the main thread, drawn into a small bitmap. */
private fun iconOf(ctx: Context, pkg: String): ImageBitmap? = runCatching {
    val d = com.diegonmarcos.superapp.ui.AppIconCache.cached(pkg) ?: com.diegonmarcos.superapp.ui.AppIconCache.load(ctx, pkg) ?: return null
    val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp); val copy = d.constantState?.newDrawable()?.mutate() ?: d
    copy.setBounds(0, 0, 48, 48); copy.draw(c)
    bmp.asImageBitmap()
}.getOrNull()

@Composable
private fun AppIcon(ctx: Context, pkg: String, sizeDp: Int) {
    val img by androidx.compose.runtime.produceState<ImageBitmap?>(null, pkg) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { iconOf(ctx, pkg) }
    }
    val m = Modifier.padding(start = 2.dp).size(sizeDp.dp)
    if (img != null) Image(img, contentDescription = pkg, modifier = m)
    else Box(m.background(Color(0x44FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
        Text(pkg.substringAfterLast('.').take(1).uppercase(), color = Color.White, fontSize = 8.sp)
    }
}
