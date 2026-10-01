package app.voxlibre

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.*

/* ---------------- ViewModel: ponte Engine <-> estado Compose ---------------- */
class RingViewModel(app: Application) : AndroidViewModel(app), RingEngine.Ui {
    val engine = RingEngine(app, this)

    var statusText by mutableStateOf("disconnected"); private set
    var connected by mutableStateOf(false); private set
    var model by mutableStateOf(""); var fw by mutableStateOf(""); var hw by mutableStateOf(""); var sn by mutableStateOf("")
    var battPct by mutableStateOf(-1); var battVolt by mutableStateOf(0); var battSt by mutableStateOf(0)
    var rssiHost by mutableStateOf(0); var storFree by mutableStateOf(-1L); var storTot by mutableStateOf(0L)
    var eventText by mutableStateOf(""); var gestureText by mutableStateOf("")
    val library = mutableStateListOf<RingEngine.Rec>()
    val logs = mutableStateListOf<Pair<String, Boolean>>()
    var snack by mutableStateOf<String?>(null)
    var mediaToggle by mutableStateOf(false)
    var onlyUnmapped by mutableStateOf(false)
    var wordWrap by mutableStateOf(false)
    var configured by mutableStateOf(engine.isConfigured())
    fun bonded(): List<Array<String>> = engine.bondedDevices()
    fun selectRing(m: String) { engine.setMac(m); configured = true; engine.toggleConnect() }
    fun refreshLibrary() = engine.refreshLibrary()

    override fun status(text: String, conn: Boolean) { statusText = text; connected = conn }
    override fun device(m: String, f: String, hw_: String, s: String) { model = m; fw = f; hw = hw_; sn = s }
    override fun telem(bp: Int, bv: Int, bs: Int, rh: Int, sf: Long, st: Long) { battPct = bp; battVolt = bv; battSt = bs; rssiHost = rh; storFree = sf; storTot = st }
    override fun event(text: String) { eventText = text }
    override fun gesture(text: String) { gestureText = text }
    override fun library(list: List<RingEngine.Rec>) { library.clear(); library.addAll(list) }
    override fun logLine(line: String, unmapped: Boolean) { logs.add(line to unmapped); if (logs.size > 2000) logs.removeAt(0) }
    override fun toast(text: String) { snack = text }

    fun setMedia(on: Boolean) { mediaToggle = on; engine.setMediaToggle(on) }
}

/* ---------------- helpers ---------------- */
private val tsFmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
private fun fmtTs(id: String): String = try { tsFmt.format(Date(id.toLong() * 1000L)) } catch (e: Exception) { id }
private fun fmtDur(ms: Int): String = if (ms >= 1000) String.format(Locale.US, "%.1fs", ms / 1000.0) else "${ms}ms"

private val Lime = Color(0xFFA6E635)
private val Muted = Color(0xFF9AA0A6)
private val BgContent = Color(0xFF0B0E13)   // content (darkest)
private val BgChrome = Color(0xFF181D25)    // top app bar + nav bar
private val BgFilter = Color(0xFF262E39)    // filter / bottom bars (lighter, stands out)
private val scheme = darkColorScheme(
    primary = Lime, onPrimary = Color(0xFF0A2000), secondary = Lime,
    background = BgContent, surface = BgChrome, surfaceVariant = Color(0xFF232A34),
    onBackground = Color(0xFFE3E3E3), onSurface = Color(0xFFE3E3E3), onSurfaceVariant = Muted,
)

/* ---------------- Activity ---------------- */
class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 1)
        setContent { MaterialTheme(colorScheme = scheme) { RingApp() } }
    }
}

/* ---------------- UI ---------------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingApp(vm: RingViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    val snackHost = remember { SnackbarHostState() }
    LaunchedEffect(vm.snack) { vm.snack?.let { snackHost.showSnackbar(it); vm.snack = null } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgChrome),
                title = {
                    Column {
                        Text("VoxLibre", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dot = if (vm.connected) Lime else if (vm.statusText == "disconnected") Color(0xFF6B7280) else Color(0xFFF5A623)
                            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                            Spacer(Modifier.width(6.dp))
                            Text(vm.statusText, style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                },
                actions = {
                    if (vm.configured) OutlinedButton(onClick = { vm.engine.toggleConnect() }, modifier = Modifier.padding(end = 4.dp)) {
                        Text(if (vm.statusText == "disconnected") "Connect" else "Disconnect")
                    }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, "Settings") }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = BgChrome) {
                navItem(tab, 0, Icons.Filled.Sensors, "Ring") { tab = 0 }
                navItem(tab, 1, Icons.Filled.Mic, "Recordings") { tab = 1; vm.refreshLibrary() }
                navItem(tab, 2, Icons.Filled.Article, "Logs") { tab = 2 }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> RingScreen(vm)
                1 -> LibraryScreen(vm)
                else -> LogsScreen(vm)
            }
        }
    }
    if (settings) SettingsDialog(vm) { settings = false }
}

@Composable
fun SettingsDialog(vm: RingViewModel, onClose: () -> Unit) {
    var bindKey by remember { mutableStateOf(vm.engine.getBindKey()) }
    var userId by remember { mutableStateOf(vm.engine.getUserId()) }
    var mac by remember { mutableStateOf(vm.engine.getMac()) }
    var picker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = {
                vm.engine.setBindKey(bindKey); vm.engine.setUserId(userId)
                vm.configured = vm.engine.isConfigured()
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        title = { Text("Settings") },
        text = {
            Column {
                Text("Pairing credentials are minted by Vocci's servers. Enter the values obtained for your ring.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = mac, onValueChange = {}, readOnly = true, label = { Text("Ring (MAC)") },
                    trailingIcon = { TextButton(onClick = { picker = true }) { Text("Choose") } },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = bindKey, onValueChange = { bindKey = it }, singleLine = true,
                    label = { Text("BIND KEY") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = userId, onValueChange = { userId = it }, singleLine = true,
                    label = { Text("USER ID") }, modifier = Modifier.fillMaxWidth())
            }
        }
    )
    if (picker) {
        val devices = remember { vm.bonded() }
        AlertDialog(
            onDismissRequest = { picker = false },
            confirmButton = { TextButton(onClick = { picker = false }) { Text("Close") } },
            title = { Text("Select ring") },
            text = {
                Column {
                    if (devices.isEmpty()) Text("No paired devices.", color = Muted)
                    devices.forEach { d ->
                        TextButton(onClick = { picker = false; vm.engine.setMac(d[1]); mac = d[1] },
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) { Text(d[0]); Text(d[1], color = Muted, fontSize = 11.sp) }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun RowScope.navItem(cur: Int, idx: Int, icon: ImageVector, label: String, onClick: () -> Unit) {
    NavigationBarItem(selected = cur == idx, onClick = onClick, icon = { Icon(icon, label) }, label = { Text(label) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RingScreen(vm: RingViewModel) {
    if (!vm.connected) { EmptyState(vm); return }
    val onSurf = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxSize()) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        // ---- Device ----
        Text(vm.model.ifEmpty { "Ring" }, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        if (vm.sn.isNotEmpty()) FlatKv("SN", vm.sn)
        if (vm.fw.isNotEmpty()) FlatKv("Firmware", vm.fw)
        if (vm.hw.isNotEmpty()) FlatKv("Hardware", vm.hw)

        // ---- Telemetry ----
        if (vm.battPct >= 0 || vm.storTot > 0 || vm.rssiHost > 0) {
            SectionDivider()
            val chips = buildList {
                if (vm.battSt != 0) add("Charging" to Icons.Filled.Bolt)
                if (vm.rssiHost > 0) {
                    val q = if (vm.rssiHost <= 60) "Strong signal" else if (vm.rssiHost <= 75) "Medium signal" else "Weak signal"
                    add("-${vm.rssiHost} dBm · $q" to Icons.Filled.SignalCellularAlt)
                }
                if (vm.battVolt > 0) add("${String.format(Locale.US, "%.2f", vm.battVolt / 1000.0)} V" to Icons.Filled.Bolt)
            }
            if (chips.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { (txt, ic) ->
                        AssistChip(onClick = {}, label = { Text(txt) },
                            leadingIcon = { Icon(ic, null, Modifier.size(16.dp)) })
                    }
                }
            }
            val battColor = if (vm.battPct <= 15) Color(0xFFE5484D) else if (vm.battPct <= 35) Color(0xFFF5A623) else Lime
            Row(Modifier.fillMaxWidth()) {
                Metric(Modifier.weight(1f), Icons.Filled.BatteryFull, "Battery",
                    if (vm.battPct >= 0) vm.battPct.toString() else "—", "%",
                    if (vm.battPct < 0) onSurf else battColor, if (vm.battPct >= 0) vm.battPct / 100f else null)
                Metric(Modifier.weight(1f), Icons.Filled.Storage, "Free",
                    if (vm.storTot > 0) String.format(Locale.US, "%.1f", vm.storFree / 1048576.0) else "—", "MB",
                    onSurf, if (vm.storTot > 0) (vm.storFree.toFloat() / vm.storTot) else null)
            }
        }

        // ---- Events / Gestures ----
        if (vm.eventText.isNotEmpty() || vm.gestureText.isNotEmpty()) SectionDivider()
        if (vm.eventText.isNotEmpty()) FlatLabeled("Event", vm.eventText)
        if (vm.gestureText.isNotEmpty()) FlatLabeled("Gesture", vm.gestureText)

      }
      // ---- Media (pinned to the bottom, above the nav bar) ----
      HorizontalDivider()
      Column(Modifier.background(BgFilter).padding(horizontal = 20.dp, vertical = 8.dp)) {
        ToggleRow("Single click → Play/Pause", vm.mediaToggle) { vm.setMedia(it) }
      }
    }
}

@Composable
fun FlatKv(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Muted, modifier = Modifier.weight(1f))
        Text(v, textAlign = TextAlign.End)
    }
}

@Composable
fun FlatLabeled(label: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label.uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(2.dp))
        Text(value)
    }
}

@Composable
fun SectionDivider() { HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color(0xFF232A33)) }

@Composable
fun Metric(mod: Modifier, icon: ImageVector, label: String, value: String, unit: String, valueColor: Color, progress: Float?) {
    Column(mod.padding(end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Muted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(label.uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 0.5.sp)
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = valueColor)
            Spacer(Modifier.width(3.dp))
            Text(unit, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
                color = valueColor, trackColor = Color(0xFF262D36),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: RingViewModel) {
    var filter by remember { mutableIntStateOf(0) }   // 0=all, 1=on ring, 2=downloaded
    val items by remember { derivedStateOf { vm.library.filter { when (filter) { 1 -> it.onRing; 2 -> it.savedLocal; else -> true } } } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (vm.library.isEmpty()) "No recordings yet" else "Nothing in this filter", color = Muted)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { r -> LibraryRow(vm, r); HorizontalDivider() }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().background(BgFilter).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                listOf("All", "On ring", "Local").forEachIndexed { i, lbl ->
                    SegmentedButton(selected = filter == i, onClick = { filter = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(lbl) }
                }
            }
            IconButton(onClick = { vm.engine.manualRefresh() }, enabled = vm.connected) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
    }
}

@Composable
fun LibraryRow(vm: RingViewModel, r: RingEngine.Rec) {
    var menu by remember { mutableStateOf(false) }
    val status: String; val color: Color
    when {
        r.onRing && r.savedLocal -> { status = "Synced"; color = Color(0xFF7EC8FF) }
        r.onRing -> { status = "On ring"; color = Lime }
        else -> { status = "On phone only"; color = Muted }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (r.savedLocal) Icons.Filled.CheckCircle else Icons.Filled.Mic, null, tint = color,
            modifier = Modifier.padding(end = 12.dp).size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(status.uppercase(), color = color, fontSize = 11.sp, letterSpacing = 0.5.sp)
            Text(fmtTs(r.id), fontSize = 15.sp)
            Text((if (r.sizeOpus >= 0) "${r.sizeOpus} B" else "—") + (r.durationMs?.let { " · " + fmtDur(it) } ?: ""),
                color = Muted, fontSize = 12.sp)
        }
        if (r.savedLocal) IconButton(onClick = { vm.engine.play(r.name) }) { Icon(Icons.Filled.PlayArrow, "Play", tint = Lime) }
        else IconButton(enabled = vm.connected,
            onClick = { if (vm.connected && !vm.engine.isDownloading) vm.engine.download(r.name) else vm.toast(if (!vm.connected) "connect the ring" else "download in progress") }) {
            Icon(Icons.Filled.Download, "Download", tint = if (vm.connected) Lime else Muted)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            if (menu) DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
                if (r.onRing) DropdownMenuItem(text = { Text("Delete from ring") }, enabled = vm.connected,
                    onClick = { menu = false; vm.engine.deleteRing(r.id) })
                if (r.savedLocal) DropdownMenuItem(text = { Text("Delete from phone") },
                    onClick = { menu = false; vm.engine.deleteLocal(r.id) })
            }
        }
    }
}

@Composable
fun LogsScreen(vm: RingViewModel) {
    if (!vm.connected) { NeedConnect(); return }
    val shown = vm.logs.filter { !vm.onlyUnmapped || it.second }
    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()
    LaunchedEffect(shown.size) { scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                .then(if (!vm.wordWrap) Modifier.horizontalScroll(hScroll) else Modifier)
                .padding(12.dp)
        ) {
            shown.forEach { (line, unm) ->
                Text(line, fontSize = 11.sp, color = if (unm) Lime else Color(0xFFB0B6BD),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    softWrap = vm.wordWrap, maxLines = if (vm.wordWrap) Int.MAX_VALUE else 1)
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().background(BgFilter).padding(horizontal = 12.dp, vertical = 4.dp)) {
            ToggleRow("Unmapped interactions only", vm.onlyUnmapped) { vm.onlyUnmapped = it }
            ToggleRow("Word wrap", vm.wordWrap) { vm.wordWrap = it }
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun NeedConnect() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.BluetoothDisabled, null, tint = Muted, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("Ring not connected", fontSize = 16.sp)
        Text("Connect the ring first", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun EmptyState(vm: RingViewModel) {
    var picker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.BluetoothDisabled, null, tint = Muted, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        if (vm.configured) {
            Text("Ring disconnected", fontSize = 18.sp)
            Text("Tap Connect to see the ring data", color = Muted, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 32.dp, end = 32.dp))
        } else {
            Text("No ring selected", fontSize = 18.sp)
            Text("Choose the ring paired on your phone", color = Muted, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 32.dp, end = 32.dp))
            Spacer(Modifier.height(16.dp))
            Button(onClick = { picker = true }) { Text("Select ring") }
        }
    }
    if (picker) {
        val devices = remember { vm.bonded() }
        AlertDialog(
            onDismissRequest = { picker = false },
            confirmButton = { TextButton(onClick = { picker = false }) { Text("Close") } },
            title = { Text("Select ring") },
            text = {
                Column {
                    if (devices.isEmpty()) Text("No paired devices.", color = Muted)
                    devices.forEach { d ->
                        TextButton(onClick = { picker = false; vm.selectRing(d[1]) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(d[0]); Text(d[1], color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        )
    }
}
