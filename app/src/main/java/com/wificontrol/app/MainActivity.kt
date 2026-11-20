package com.wificontrol.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

private val Blue = Color(0xFF1565C0)
private val Cyan = Color(0xFF00ACC1)
private var keepCallback: ConnectivityManager.NetworkCallback? = null

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, secondary = Cyan)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf("home") }
    if (screen == "home") WifiApp { screen = "router" } else RouterScreen { screen = "home" }
}

fun toast(c: Context, m: String) =
    Handler(Looper.getMainLooper()).post { Toast.makeText(c, m, Toast.LENGTH_LONG).show() }

fun wifiMgr(c: Context) = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

fun band(f: Int) = when { f in 2400..2500 -> "2.4 GHz"; f in 4900..5900 -> "5 GHz"; f >= 5925 -> "6 GHz"; else -> "—" }
fun percent(rssi: Int) = ((rssi + 100) * 2).coerceIn(0, 100)
fun quality(p: Int) = when { p >= 70 -> "قوية"; p >= 40 -> "متوسطة"; else -> "ضعيفة" }
fun ip(i: Int) = "${i and 255}.${i shr 8 and 255}.${i shr 16 and 255}.${i shr 24 and 255}"
fun security(cap: String) = when {
    cap.contains("SAE") || cap.contains("WPA3") -> "WPA3"
    cap.contains("WPA2") || cap.contains("RSN") -> "WPA2"
    cap.contains("WPA") -> "WPA"
    cap.contains("WEP") -> "WEP"
    else -> "مفتوحة"
}
fun standard(s: Int) = when (s) {
    4 -> "Wi-Fi 4 (802.11n)"; 5 -> "Wi-Fi 5 (802.11ac)"; 6 -> "Wi-Fi 6 (802.11ax)"; 8 -> "Wi-Fi 7 (802.11be)"; else -> "غير معروف"
}

@Suppress("DEPRECATION")
fun readInfo(c: Context): List<Pair<String, String>> {
    val wm = wifiMgr(c)
    if (!wm.isWifiEnabled) return emptyList()
    val i = wm.connectionInfo ?: return emptyList()
    if (i.networkId == -1) return emptyList()
    val d = wm.dhcpInfo
    val ssid = i.ssid.trim('"').let { if (it == "<unknown ssid>") "غير متاح (فعّل الموقع وامنح الإذن)" else it }
    val p = percent(i.rssi)
    val rows = mutableListOf(
        "اسم الشبكة (SSID)" to ssid,
        "قوة الإشارة" to "$p% - ${quality(p)} (${i.rssi} dBm)",
        "السرعة" to "${i.linkSpeed} Mbps",
        "التردد" to "${i.frequency} MHz (${band(i.frequency)})",
        "عنوان IP" to ip(d.ipAddress),
        "البوابة (Gateway)" to ip(d.gateway),
        "قناع الشبكة" to ip(d.netmask),
        "DNS 1" to ip(d.dns1),
        "DNS 2" to ip(d.dns2),
        "BSSID" to (i.bssid ?: "—")
    )
    if (Build.VERSION.SDK_INT >= 30) rows.add(3, "الجيل" to standard(i.wifiStandard))
    return rows
}

fun connect(c: Context, ssid: String, pass: String, sec: String) {
    val b = WifiNetworkSpecifier.Builder().setSsid(ssid)
    if (pass.isNotEmpty()) if (sec == "WPA3") b.setWpa3Passphrase(pass) else b.setWpa2Passphrase(pass)
    val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .setNetworkSpecifier(b.build()).build()
    val cm = c.getSystemService(ConnectivityManager::class.java)
    keepCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { cm.bindProcessToNetwork(n); toast(c, "تم الاتصال بـ $ssid ✅") }
        override fun onUnavailable() { toast(c, "فشل الاتصال، تأكد من كلمة المرور ❌") }
    }
    keepCallback = cb
    cm.requestNetwork(req, cb)
}

fun saveNetwork(c: Context, ssid: String, pass: String, sec: String) {
    val b = WifiNetworkSuggestion.Builder().setSsid(ssid)
    if (pass.isNotEmpty()) if (sec == "WPA3") b.setWpa3Passphrase(pass) else b.setWpa2Passphrase(pass)
    val st = wifiMgr(c).addNetworkSuggestions(listOf(b.build()))
    toast(c, if (st == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS)
        "تم حفظ الشبكة. وافق على إشعار النظام لتفعيلها ✅" else "تعذّر الحفظ (كود $st)")
}


fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")

fun makeQr(ssid: String, pass: String, sec: String): Bitmap {
    val open = sec == "مفتوحة"
    val text = "WIFI:T:${if (open) "nopass" else "WPA"};S:${esc(ssid)};" + (if (open) "" else "P:${esc(pass)};") + ";"
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
    for (x in 0 until 512) for (y in 0 until 512) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    return bmp
}

fun speedTest(onUpdate: (String, Boolean) -> Unit) {
    Thread {
        try {
            var ping = Long.MAX_VALUE
            repeat(3) {
                val t = System.nanoTime()
                val h = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
                h.connectTimeout = 6000; h.readTimeout = 6000
                h.responseCode; h.disconnect()
                ping = minOf(ping, (System.nanoTime() - t) / 1_000_000)
            }
            onUpdate("Ping: $ping ms\nجاري قياس سرعة التحميل...", true)
            val c = URL("https://speed.cloudflare.com/__down?bytes=20000000").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000
            val start = System.nanoTime(); var total = 0L; val buf = ByteArray(65536)
            c.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    total += n
                    val sec = (System.nanoTime() - start) / 1e9
                    if (sec > 0.5) onUpdate("Ping: $ping ms\nتحميل: %.1f Mbps ...".format(total * 8 / 1e6 / sec), true)
                    if (sec > 10) break
                }
            }
            val sec = (System.nanoTime() - start) / 1e9
            onUpdate("Ping: $ping ms\nالتحميل: %.1f Mbps  ✅".format(total * 8 / 1e6 / sec), false)
        } catch (e: Exception) {
            onUpdate("تعذّر الاختبار. تأكد من اتصالك بالإنترنت.", false)
        }
    }.start()
}

fun open(c: Context, action: String) {
    try { c.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: Exception) {
        try { c.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { toast(c, "هذه الصفحة غير متاحة في جهازك") }
    }
}

@Suppress("MissingPermission", "DEPRECATION")
@Composable
fun WifiApp(onRouter: () -> Unit) {
    val ctx = LocalContext.current
    val wm = remember { wifiMgr(ctx) }
    var enabled by remember { mutableStateOf(wm.isWifiEnabled) }
    var info by remember { mutableStateOf(readInfo(ctx)) }
    var scans by remember { mutableStateOf(listOf<ScanResult>()) }
    var dialog by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var speed by remember { mutableStateOf("اضغط ابدأ لقياس Ping وسرعة التحميل") }
    var running by remember { mutableStateOf(false) }
    var qrSsid by remember { mutableStateOf("") }
    var qrPass by remember { mutableStateOf("") }
    var qrSec by remember { mutableStateOf("WPA2") }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var showCur by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("wifi_app", Context.MODE_PRIVATE) }

    fun hasPerm() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun refresh(scan: Boolean) {
        enabled = wm.isWifiEnabled
        info = readInfo(ctx)
        if (hasPerm() && enabled) {
            if (scan) wm.startScan()
            scans = wm.scanResults.filter { it.SSID.isNotBlank() }.groupBy { it.SSID }
                .map { e -> e.value.maxByOrNull { it.level }!! }.sortedByDescending { it.level }
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh(true) }

    DisposableEffect(Unit) {
        val owner = ctx as ComponentActivity
        val obs = object : DefaultLifecycleObserver { override fun onResume(o: LifecycleOwner) { refresh(true) } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val shortcuts = listOf(
        "إعدادات الواي فاي" to Settings.ACTION_WIFI_SETTINGS,
        "الشبكات المحفوظة" to "android.settings.WIFI_SAVED_NETWORK_SETTINGS",
        "إعدادات IP" to Settings.ACTION_WIFI_IP_SETTINGS,
        "لوحة الإنترنت" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
        "الشبكات اللاسلكية والهوتسبوت" to Settings.ACTION_WIRELESS_SETTINGS,
        "وضع الطيران" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "استهلاك البيانات" to Settings.ACTION_DATA_USAGE_SETTINGS,
        "الموقع" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "VPN" to "android.settings.VPN_SETTINGS",
        "إعدادات الشبكة" to Settings.ACTION_NETWORK_OPERATOR_SETTINGS
    )

    LazyColumn(Modifier.fillMaxSize().background(Color(0xFFF2F5FA))) {
        item { Header() }
        item {
            Card2("حالة الواي فاي") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (enabled) Color(0xFF2E7D32) else Color(0xFFC62828)))
                    Spacer(Modifier.width(8.dp))
                    Text(if (enabled) "مفعّل" else "مغلق", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Button(onClick = { open(ctx, Settings.Panel.ACTION_WIFI) }) { Text(if (enabled) "إيقاف / تغيير" else "تشغيل") }
                }
                Text("ملاحظة: أندرويد 10+ لا يسمح لأي تطبيق بتشغيل/إيقاف الواي فاي مباشرة، فيفتح لك التطبيق لوحة النظام للضغط عليها.",
                    fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            Card2("الاتصال الحالي (اضغط لعرض الاسم وكلمة السر)", onClick = { showCur = true }) {
                if (info.isEmpty()) Text(if (enabled) "غير متصل بأي شبكة" else "الواي فاي مغلق", color = Color.Gray)
                info.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(k, color = Color.Gray, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Text(v, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                    HorizontalDivider(color = Color(0xFFEEEEEE))
                }
                if (!hasPerm()) {
                    Button(onClick = { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("منح إذن الموقع لإظهار اسم الشبكة")
                    }
                }
            }
        }
        item {
            Card2("التحكم الكامل في الراوتر") {
                Text("سجّل دخول لوحة الراوتر من داخل التطبيق، وغيّر اسم الواي فاي وكلمته والقناة وDHCP وفتح البورتات وحجب الأجهزة وكل الإعدادات الداخلية، مع دليل مبسّط لكل إعداد.", fontSize = 13.sp, color = Color.Gray)
                Button(onClick = onRouter, modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) { Text("فتح لوحة تحكم الراوتر") }
            }
        }
        item {
            Card2("اختبار سرعة الإنترنت") {
                Text(speed, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Button(enabled = !running, modifier = Modifier.padding(top = 10.dp), onClick = {
                    running = true; speed = "جاري قياس Ping..."
                    speedTest { t, r -> speed = t; running = r }
                }) { Text(if (running) "جاري الاختبار..." else "ابدأ الاختبار") }
            }
        }
        item {
            Card2("مشاركة الشبكة بـ QR") {
                OutlinedTextField(qrSsid, { qrSsid = it }, label = { Text("اسم الشبكة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("مفتوحة", "WPA2", "WPA3").forEach { o ->
                        FilterChip(selected = qrSec == o, onClick = { qrSec = o }, label = { Text(o) })
                    }
                }
                if (qrSec != "مفتوحة") OutlinedTextField(qrPass, { qrPass = it }, label = { Text("كلمة المرور") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { info.firstOrNull()?.second?.takeIf { !it.startsWith("غير متاح") }?.let { qrSsid = it; qrPass = prefs.getString("pw_$it", "") ?: "" } }) { Text("الشبكة الحالية") }
                    Button(onClick = { if (qrSsid.isNotBlank()) qr = makeQr(qrSsid, qrPass, qrSec) else toast(ctx, "اكتب اسم الشبكة") }) { Text("إنشاء QR") }
                }
                qr?.let {
                    Image(it.asImageBitmap(), "QR", Modifier.padding(top = 12.dp).size(220.dp).align(Alignment.CenterHorizontally))
                    Text("اطلب من صديقك مسح الكود بكاميرا الموبايل ليتصل مباشرة", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
        item {
            Card2("الشبكات المتاحة") {
                Row {
                    OutlinedButton(onClick = { if (hasPerm()) refresh(true) else permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("بحث عن شبكات") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { dialog = Triple("", "WPA2", true) }) { Text("إضافة شبكة يدويًا") }
                }
                if (scans.isEmpty()) Text("اضغط بحث لعرض الشبكات القريبة", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                scans.forEach { s ->
                    val p = percent(s.level); val sec = security(s.capabilities)
                    Row(Modifier.fillMaxWidth().clickable { dialog = Triple(s.SSID, sec, false) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.SSID, fontWeight = FontWeight.Bold)
                            Text("${if (sec == "مفتوحة") "🔓" else "🔒"} $sec  •  ${band(s.frequency)}", fontSize = 12.sp, color = Color.Gray)
                        }
                        Text("$p%", color = if (p >= 70) Color(0xFF2E7D32) else if (p >= 40) Color(0xFFEF6C00) else Color(0xFFC62828), fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = Color(0xFFEEEEEE))
                }
            }
        }
        item {
            Card2("اختصارات إعدادات الواي فاي والشبكة") {
                shortcuts.chunked(2).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.forEach { (t, a) ->
                            FilledTonalButton(onClick = { open(ctx, a) }, modifier = Modifier.weight(1f).height(56.dp),
                                shape = RoundedCornerShape(12.dp)) { Text(t, fontSize = 12.sp) }
                        }
                        if (r.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showCur) {
        val cur = info.firstOrNull()?.second?.takeIf { !it.startsWith("غير متاح") } ?: ""
        var nm by remember { mutableStateOf(cur) }
        var pw by remember { mutableStateOf(prefs.getString("pw_$cur", "") ?: "") }
        var show by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showCur = false },
            title = { Text("بيانات الشبكة الحالية") },
            text = {
                Column {
                    OutlinedTextField(nm, { nm = it }, label = { Text("اسم الشبكة") }, singleLine = true)
                    OutlinedTextField(pw, { pw = it }, label = { Text("كلمة السر") }, singleLine = true,
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { show = !show }) { Text(if (show) "إخفاء الكلمة" else "إظهار الكلمة") }
                    Text("أندرويد يمنع أي تطبيق من قراءة كلمة سر الواي فاي المحفوظة. اكتبها هنا مرة واحدة وسيحفظها التطبيق ويعرضها لك دائمًا. ولو نسيتها: إعدادات الواي فاي ← الشبكة ← مشاركة، أو من لوحة الراوتر.",
                        fontSize = 12.sp, color = Color.Gray)
                    if (cur.isEmpty()) Button(onClick = { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("منح إذن الموقع لجلب الاسم") }
                }
            },
            confirmButton = {
                Button(onClick = { prefs.edit().putString("pw_$nm", pw).apply(); toast(ctx, "تم الحفظ ✅"); showCur = false }) { Text("حفظ") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("wifi", pw))
                        toast(ctx, "تم نسخ كلمة السر")
                    }) { Text("نسخ") }
                    TextButton(onClick = {
                        if (nm.isNotBlank()) { qrSsid = nm; qrPass = pw; qrSec = if (pw.isEmpty()) "مفتوحة" else "WPA2"; qr = makeQr(nm, pw, qrSec) }
                        showCur = false
                    }) { Text("QR") }
                    TextButton(onClick = { showCur = false }) { Text("إغلاق") }
                }
            }
        )
    }

    dialog?.let { (ssid0, sec0, manual) ->
        var ssid by remember { mutableStateOf(ssid0) }
        var pass by remember { mutableStateOf("") }
        var sec by remember { mutableStateOf(sec0) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (manual) "إضافة شبكة" else "الاتصال بـ $ssid0") },
            text = {
                Column {
                    if (manual) OutlinedTextField(ssid, { ssid = it }, label = { Text("اسم الشبكة") }, singleLine = true)
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("مفتوحة", "WPA2", "WPA3").forEach { o ->
                            FilterChip(selected = sec == o || (o == "WPA2" && sec in listOf("WPA", "WEP")), onClick = { sec = o }, label = { Text(o) })
                        }
                    }
                    if (sec != "مفتوحة") OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = { prefs.edit().putString("pw_$ssid", pass).apply(); connect(ctx, ssid, if (sec == "مفتوحة") "" else pass, sec); dialog = null }) { Text("اتصال") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { prefs.edit().putString("pw_$ssid", pass).apply(); saveNetwork(ctx, ssid, if (sec == "مفتوحة") "" else pass, sec); dialog = null }) { Text("حفظ الشبكة") }
                    TextButton(onClick = { dialog = null }) { Text("إلغاء") }
                }
            }
        )
    }
}

@Composable
fun Header() {
    Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Blue, Cyan)))
        .padding(top = 24.dp, bottom = 28.dp, start = 20.dp, end = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.18f))) {
                Image(painterResource(R.drawable.ic_logo_fg), null, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("واي فاي كونترول", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("تحكّم كامل في شبكتك وإعداداتها", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun Card2(title: String, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Blue)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
