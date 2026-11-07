@file:OptIn(ExperimentalMaterial3Api::class)
/*
 * SMART REMOTE – one-file Android app (Kotlin + Jetpack Compose)
 *  Home screen -> [TV Remote] opens the remote GUI, [Smart Camera] opens offline AI camera.
 *
 * ── SETUP (3 steps) ─────────────────────────────────────────────────────────────
 * 1) Android Studio > New Project > Empty Activity (Compose), package com.example.tvremote.
 *    Replace MainActivity.kt with this file.
 * 2) app/build.gradle.kts  dependencies { ... }
 *      implementation(platform("androidx.compose:compose-bom:2024.10.01"))
 *      implementation("androidx.compose.material3:material3")
 *      implementation("androidx.activity:activity-compose:1.9.3")
 *      implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
 *      val camx = "1.3.4"
 *      implementation("androidx.camera:camera-core:$camx"); implementation("androidx.camera:camera-camera2:$camx")
 *      implementation("androidx.camera:camera-lifecycle:$camx"); implementation("androidx.camera:camera-view:$camx")
 *      implementation("com.google.mlkit:text-recognition:16.0.1")      // OCR, bundled = offline (Latin script)
 *      implementation("com.google.mlkit:image-labeling:17.0.9")        // object labels, bundled = offline
 *      implementation("com.google.mlkit:barcode-scanning:17.3.0")      // barcodes/QR, bundled = offline
 *      implementation("com.google.mlkit:language-id:17.0.6")           // detect language, offline
 *      implementation("com.google.mlkit:translate:17.0.3")             // offline after one-time language download
 *    Optional other scripts: text-recognition-devanagari / -chinese / -japanese / -korean (16.0.1)
 * 3) AndroidManifest.xml (inside <manifest>):
 *      <uses-permission android:name="android.permission.CAMERA"/>
 *      <uses-permission android:name="android.permission.INTERNET"/>
 *      <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
 *      <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
 *      <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE"/>
 *      <uses-permission android:name="android.permission.TRANSMIT_IR"/>
 *      <uses-feature android:name="android.hardware.consumerir" android:required="false"/>
 *    and on <application>: android:usesCleartextTraffic="true"
 */
package com.example.tvremote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

// ════════════════════════════════ ACTIVITY + HOME ════════════════════════════════
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { Root() } } }
    }
}

@Composable
fun Root() {
    var screen by remember { mutableStateOf("home") }
    BackHandler(enabled = screen != "home") { screen = "home" }
    when (screen) {
        "remote" -> RemoteScreen { screen = "home" }
        "camera" -> CameraScreen { screen = "home" }
        else -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Smart Remote", style = MaterialTheme.typography.headlineLarge)
            Button({ screen = "remote" }, Modifier.fillMaxWidth().height(90.dp)) { Text("📺  TV Remote", style = MaterialTheme.typography.titleLarge) }
            Button({ screen = "camera" }, Modifier.fillMaxWidth().height(90.dp)) { Text("📷  Smart Camera", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

// ════════════════════════════════ TV REMOTE ════════════════════════════════
enum class TvKey { POWER, VOL_UP, VOL_DOWN, MUTE, CH_UP, CH_DOWN, UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, MENU }
enum class Brand { ROKU, SAMSUNG, LG, SONY, UNKNOWN }
data class TvDevice(val ip: String, val name: String, val brand: Brand)

@Composable
fun RemoteScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ir = remember { IrSender(ctx) }
    var devices by remember { mutableStateOf(listOf<TvDevice>()) }
    var selected by remember { mutableStateOf<TvDevice?>(null) }
    var irMode by remember { mutableStateOf(false) }
    var irBrand by remember { mutableStateOf(Brand.SAMSUNG) }
    var status by remember { mutableStateOf("Tap Scan to find TVs on your Wi-Fi (no internet needed)") }
    var scanning by remember { mutableStateOf(false) }

    fun press(k: TvKey) { scope.launch {
        val ok = if (irMode) ir.send(irBrand, k) else selected?.let { LanControl.send(it, k) } ?: false
        status = if (ok) "Sent ${k.name}" else if (irMode) "IR failed (no emitter / unsupported key)" else "Select a Roku or Samsung TV first"
    } }

    Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("← Back") }
            Text("TV Remote", style = MaterialTheme.typography.titleLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !scanning, onClick = { scope.launch {
                scanning = true; status = "Scanning…"
                devices = Discovery.scan(ctx); scanning = false; status = "Found ${devices.size} device(s)"
            } }) { Text(if (scanning) "Scanning…" else "Scan") }
            FilterChip(irMode, { irMode = !irMode }, enabled = ir.available,
                label = { Text(if (ir.available) "IR blaster" else "No IR on phone") })
        }
        if (irMode) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Brand.SAMSUNG, Brand.LG, Brand.SONY).forEach { FilterChip(irBrand == it, { irBrand = it }, label = { Text(it.name) }) }
        } else LazyColumn(Modifier.heightIn(max = 130.dp)) {
            items(devices) { d ->
                ListItem(headlineContent = { Text("${d.brand}  ${d.ip}") }, supportingContent = { Text(d.name, maxLines = 1) },
                    trailingContent = { RadioButton(selected?.ip == d.ip, { selected = d }) })
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        @Composable fun B(l: String, k: TvKey, m: Modifier = Modifier) = FilledTonalButton({ press(k) }, m.height(52.dp)) { Text(l) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            B("⏻", TvKey.POWER, Modifier.weight(1f)); B("Mute", TvKey.MUTE, Modifier.weight(1f)); B("Home", TvKey.HOME, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                B("Vol +", TvKey.VOL_UP, Modifier.fillMaxWidth()); B("Vol –", TvKey.VOL_DOWN, Modifier.fillMaxWidth()) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                B("Ch +", TvKey.CH_UP, Modifier.fillMaxWidth()); B("Ch –", TvKey.CH_DOWN, Modifier.fillMaxWidth()) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { B("▲", TvKey.UP, Modifier.width(100.dp)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            B("◀", TvKey.LEFT, Modifier.weight(1f)); B("OK", TvKey.OK, Modifier.weight(1f)); B("▶", TvKey.RIGHT, Modifier.weight(1f)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { B("▼", TvKey.DOWN, Modifier.width(100.dp)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            B("Back", TvKey.BACK, Modifier.weight(1f)); B("Menu", TvKey.MENU, Modifier.weight(1f)) }
    }
}

/** Finds TVs on the local Wi-Fi via SSDP. No internet needed. */
object Discovery {
    suspend fun scan(ctx: Context, timeoutMs: Int = 3500): List<TvDevice> = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tvremote").apply { setReferenceCounted(false); acquire() }
        val found = LinkedHashMap<String, TvDevice>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 600
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) try {
                    val p = DatagramPacket(buf, buf.size); s.receive(p)
                    val t = String(p.data, 0, p.length); val ip = p.address.hostAddress ?: continue
                    val l = t.lowercase()
                    val brand = when {
                        "roku" in l -> Brand.ROKU; "samsung" in l -> Brand.SAMSUNG
                        "webos" in l || "lge" in l -> Brand.LG; "bravia" in l || "sony" in l -> Brand.SONY
                        "mediarenderer" in l || "tv" in l -> Brand.UNKNOWN; else -> continue }
                    val name = Regex("(?im)^SERVER:\\s*(.+)$").find(t)?.groupValues?.get(1)?.trim() ?: ip
                    if (brand != Brand.UNKNOWN || ip !in found) found[ip] = TvDevice(ip, name, brand)
                } catch (_: SocketTimeoutException) { }
            }
        } finally { lock.release() }
        found.values.toList()
    }
}

/** Wi-Fi/LAN control: Roku (HTTP) and Samsung Tizen (WebSocket). LG/Sony: use IR. */
object LanControl {
    private val roku = mapOf(TvKey.POWER to "Power", TvKey.VOL_UP to "VolumeUp", TvKey.VOL_DOWN to "VolumeDown", TvKey.MUTE to "VolumeMute",
        TvKey.CH_UP to "ChannelUp", TvKey.CH_DOWN to "ChannelDown", TvKey.UP to "Up", TvKey.DOWN to "Down", TvKey.LEFT to "Left",
        TvKey.RIGHT to "Right", TvKey.OK to "Select", TvKey.BACK to "Back", TvKey.HOME to "Home", TvKey.MENU to "Info")
    private val sam = mapOf(TvKey.POWER to "KEY_POWER", TvKey.VOL_UP to "KEY_VOLUP", TvKey.VOL_DOWN to "KEY_VOLDOWN", TvKey.MUTE to "KEY_MUTE",
        TvKey.CH_UP to "KEY_CHUP", TvKey.CH_DOWN to "KEY_CHDOWN", TvKey.UP to "KEY_UP", TvKey.DOWN to "KEY_DOWN", TvKey.LEFT to "KEY_LEFT",
        TvKey.RIGHT to "KEY_RIGHT", TvKey.OK to "KEY_ENTER", TvKey.BACK to "KEY_RETURN", TvKey.HOME to "KEY_HOME", TvKey.MENU to "KEY_MENU")

    suspend fun send(tv: TvDevice, key: TvKey): Boolean = withContext(Dispatchers.IO) {
        try {
            when (tv.brand) {
                Brand.ROKU -> { val c = URL("http://${tv.ip}:8060/keypress/${roku[key]}").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"; c.connectTimeout = 2000; c.readTimeout = 2000; c.responseCode in 200..299 }
                Brand.SAMSUNG -> samsung(tv.ip, sam[key]!!)
                else -> false
            }
        } catch (e: Exception) { false }
    }

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }
    private val client by lazy {
        val c = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder().sslSocketFactory(c.socketFactory, trustAll).hostnameVerifier { _, _ -> true }.build()
    }
    private var sock: WebSocket? = null
    private var sockIp: String? = null

    private suspend fun samsung(ip: String, k: String): Boolean {
        if (sock == null || sockIp != ip) {
            val name = Base64.encodeToString("SmartRemote".toByteArray(), Base64.NO_WRAP)
            sock = withTimeoutOrNull(15000) {
                suspendCancellableCoroutine<WebSocket?> { cont ->
                    client.newWebSocket(Request.Builder().url("wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name").build(),
                        object : WebSocketListener() {
                            override fun onMessage(ws: WebSocket, text: String) { if ("ms.channel.connect" in text && cont.isActive) cont.resume(ws) }
                            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) { sock = null; if (cont.isActive) cont.resume(null) }
                            override fun onClosed(ws: WebSocket, code: Int, reason: String) { sock = null }
                        })
                }
            }
            sockIp = ip
        }
        return sock?.send("""{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$k","Option":"false","TypeOfRemote":"SendRemoteKey"}}""") ?: false
    }
}

/** IR blaster: fully offline, needs a phone with an IR emitter. Codes may vary by TV model. */
class IrSender(ctx: Context) {
    private val ir = ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    val available get() = ir?.hasIrEmitter() == true
    suspend fun send(b: Brand, k: TvKey): Boolean = withContext(Dispatchers.IO) {
        if (!available) return@withContext false
        when (b) {
            Brand.LG -> lg[k]?.let { ir!!.transmit(38000, bits(intArrayOf(9000, 4500), 0x04, 0x04.inv() and 255, it, it.inv() and 255)); true } ?: false
            Brand.SAMSUNG -> sam[k]?.let { ir!!.transmit(38000, bits(intArrayOf(4500, 4500), 0x07, 0x07, it, it.inv() and 255)); true } ?: false
            Brand.SONY -> sony[k]?.let { c -> repeat(3) { ir!!.transmit(40000, sirc(1, c)); delay(30) }; true } ?: false
            else -> false
        }
    }
    private val lg = mapOf(TvKey.POWER to 0x08, TvKey.VOL_UP to 0x02, TvKey.VOL_DOWN to 0x03, TvKey.MUTE to 0x09, TvKey.CH_UP to 0x00, TvKey.CH_DOWN to 0x01,
        TvKey.UP to 0x40, TvKey.DOWN to 0x41, TvKey.LEFT to 0x07, TvKey.RIGHT to 0x06, TvKey.OK to 0x44, TvKey.BACK to 0x28, TvKey.HOME to 0x7C, TvKey.MENU to 0x43)
    private val sam = mapOf(TvKey.POWER to 0x02, TvKey.VOL_UP to 0x07, TvKey.VOL_DOWN to 0x0B, TvKey.MUTE to 0x0F, TvKey.CH_UP to 0x48, TvKey.CH_DOWN to 0x44,
        TvKey.UP to 0x60, TvKey.DOWN to 0x61, TvKey.LEFT to 0x65, TvKey.RIGHT to 0x62, TvKey.OK to 0x68, TvKey.BACK to 0x58, TvKey.MENU to 0x1A)
    private val sony = mapOf(TvKey.POWER to 0x15, TvKey.VOL_UP to 0x12, TvKey.VOL_DOWN to 0x13, TvKey.MUTE to 0x14, TvKey.CH_UP to 0x10, TvKey.CH_DOWN to 0x11,
        TvKey.UP to 0x74, TvKey.DOWN to 0x75, TvKey.LEFT to 0x34, TvKey.RIGHT to 0x33, TvKey.OK to 0x65, TvKey.BACK to 0x63, TvKey.HOME to 0x60, TvKey.MENU to 0x60)
    private fun bits(hdr: IntArray, vararg bytes: Int): IntArray {
        val p = ArrayList<Int>(); hdr.forEach { p += it }
        for (b in bytes) for (i in 0..7) { p += 560; p += if ((b shr i) and 1 == 1) 1690 else 560 }
        p += 560; return p.toIntArray()
    }
    private fun sirc(addr: Int, cmd: Int): IntArray {
        val p = ArrayList<Int>(); p += 2400; p += 600
        fun bit(b: Int) { p += if (b == 1) 1200 else 600; p += 600 }
        for (i in 0..6) bit((cmd shr i) and 1); for (i in 0..4) bit((addr shr i) and 1)
        return p.toIntArray()
    }
}

// ════════════════════════════════ SMART CAMERA (all on-device) ════════════════════════════════
data class Scan(val shop: String = "", val things: List<String> = emptyList(), val models: List<String> = emptyList(),
                val codes: List<String> = emptyList(), val text: String = "", val translated: String = "")

class VisionAnalyzer(private val target: () -> String, private val onResult: (Scan) -> Unit, private val onStatus: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
    private val barcode = BarcodeScanning.getClient()
    private val langId = LanguageIdentification.getClient()
    private val translators = HashMap<String, Translator>()
    private var last = 0L
    private var lastText = ""
    private var lastTarget = ""
    private var cur = Scan()
    private val modelRegex = Regex("\\b(?=[A-Za-z0-9-]*\\d)(?=[A-Za-z0-9-]*[A-Za-z])[A-Za-z0-9][A-Za-z0-9-]{3,}\\b")

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val now = System.currentTimeMillis()
        if (media == null || now - last < 800) { proxy.close(); return }
        last = now
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        val t1 = ocr.process(img); val t2 = labeler.process(img); val t3 = barcode.process(img)
        Tasks.whenAllComplete(t1, t2, t3).addOnCompleteListener {
            try {
                val txt = if (t1.isSuccessful) t1.result else null
                val lines = txt?.textBlocks?.flatMap { it.lines } ?: emptyList()
                // Shop name = the biggest text in view (signboards use the largest lettering)
                val shop = lines.maxByOrNull { it.boundingBox?.height() ?: 0 }?.text?.takeIf { it.length >= 2 } ?: ""
                val full = (txt?.text ?: "").take(300)
                val models = modelRegex.findAll(full).map { it.value }.distinct().take(5).toList()
                val things = if (t2.isSuccessful) t2.result.take(4).map { "${it.text} ${(it.confidence * 100).toInt()}%" } else emptyList()
                val codes = if (t3.isSuccessful) t3.result.mapNotNull { it.displayValue ?: it.rawValue } else emptyList()
                val same = full == lastText && target() == lastTarget
                cur = Scan(shop, things, models, codes, full, if (same) cur.translated else "")
                onResult(cur)
                if (!same) { lastText = full; lastTarget = target(); translate(full, target()) }
            } finally { proxy.close() }
        }
    }

    private fun translate(text: String, tgt: String) {
        if (text.isBlank()) return
        langId.identifyLanguage(text).addOnSuccessListener { code ->
            val src = TranslateLanguage.fromLanguageTag(code)
            if (code == "und" || src == null) return@addOnSuccessListener
            if (src == tgt) { cur = cur.copy(translated = text); onResult(cur); return@addOnSuccessListener }
            val tr = translators.getOrPut("$src>$tgt") {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()) }
            onStatus("Preparing language pack (needs internet only once)…")
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).addOnSuccessListener {
                tr.translate(text).addOnSuccessListener { out -> cur = cur.copy(translated = out); onResult(cur); onStatus("") }
            }.addOnFailureListener { onStatus("Language pack missing: connect to internet once to download it") }
        }
    }
}

@Composable
fun CameraScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as ComponentActivity
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    var scan by remember { mutableStateOf(Scan()) }
    var status by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(TranslateLanguage.ENGLISH) }
    val targetNow = rememberUpdatedState(target)
    val analyzer = remember { VisionAnalyzer({ targetNow.value }, { scan = it }, { status = it }) }
    val exec = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { exec.shutdown() } }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("← Back") }
            Text("Smart Camera (offline)", style = MaterialTheme.typography.titleLarge)
        }
        if (!granted) { Text("Camera permission is needed.", Modifier.padding(16.dp)); return }
        AndroidView(modifier = Modifier.fillMaxWidth().weight(0.42f), factory = { c ->
            PreviewView(c).also { pv ->
                val f = ProcessCameraProvider.getInstance(c)
                f.addListener({
                    val p = f.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                    val ia = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        .also { it.setAnalyzer(exec, analyzer) }
                    p.unbindAll(); p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, ia)
                }, ContextCompat.getMainExecutor(c))
            }
        })
        Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Translate to:", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("EN" to TranslateLanguage.ENGLISH, "HI" to TranslateLanguage.HINDI, "ES" to TranslateLanguage.SPANISH,
                    "FR" to TranslateLanguage.FRENCH, "DE" to TranslateLanguage.GERMAN, "AR" to TranslateLanguage.ARABIC)
                    .forEach { (n, c) -> FilterChip(target == c, { target = c }, label = { Text(n) }) }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            Info("🏪 Shop / sign name", scan.shop)
            Info("🔎 Things detected", scan.things.joinToString("\n"))
            Info("🏷 Model / product codes", scan.models.joinToString(", "))
            Info("▮▯ Barcode / QR", scan.codes.joinToString("\n"))
            Info("📝 Text seen", scan.text)
            Info("🌐 Translation", scan.translated)
        }
    }
}

@Composable
fun Info(title: String, body: String) {
    if (body.isBlank()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
