package com.wificontrol.app

import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

fun fillJs(u: String, p: String) = """
(function(){var P=document.querySelector('input[type=password]');if(!P)return;
var U=null;document.querySelectorAll('input').forEach(function(i){var t=(i.type||'text').toLowerCase();
if((t=='text'||t=='email')&&(i.compareDocumentPosition(P)&4))U=i;});
function s(e,v){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v);
e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
if(U)s(U,${JSONObject.quote(u)});s(P,${JSONObject.quote(p)});})();
"""

private val guide = listOf(
    Triple("📶 تغيير اسم الواي فاي وكلمته", "القائمة: Wireless / WLAN / Wi-Fi", "غيّر SSID (اسم الشبكة) وكلمة المرور، واختر الحماية WPA2 أو WPA3 (تجنّب WEP وفتح الشبكة)."),
    Triple("📡 القناة والتردد", "Wireless ← Advanced / Channel", "للتردد 2.4GHz اختر قناة 1 أو 6 أو 11 أو Auto. استخدم 5GHz للسرعة والمسافات القريبة، و2.4GHz للمسافات البعيدة."),
    Triple("👥 شبكة الضيوف", "Guest Network", "أنشئ شبكة منفصلة للضيوف بكلمة سر مختلفة بدون الوصول لأجهزتك."),
    Triple("🚫 حجب أو السماح لأجهزة", "MAC Filter / Access Control", "احجب جهازًا بعنوان MAC الخاص به، أو اسمح فقط لأجهزتك المعروفة."),
    Triple("🌐 DHCP وحجز IP ثابت لجهاز", "LAN / DHCP / Address Reservation", "اربط جهازًا بعنوان IP ثابت، وحدّد نطاق العناوين وعدد الأجهزة."),
    Triple("🔓 فتح بورت (Port Forwarding)", "Forwarding / NAT / Virtual Server", "وجّه منفذًا معينًا لجهاز داخلي (كاميرا، سيرفر، ألعاب). افتح فقط ما تحتاجه."),
    Triple("🧭 تغيير DNS", "Internet / WAN / DNS", "جرّب 1.1.1.1 أو 8.8.8.8 لسرعة أكبر واستقرار أفضل."),
    Triple("👨‍👩‍👧 الرقابة الأبوية والجدولة", "Parental Control / Schedule", "حدّد أوقات الإنترنت للأجهزة وامنع مواقع معينة."),
    Triple("⚡ QoS (أولوية السرعة)", "QoS / Bandwidth Control", "أعطِ أولوية لجهاز أو تطبيق، أو حدّد أقصى سرعة لكل جهاز."),
    Triple("🔑 تغيير كلمة سر الراوتر نفسه", "Administration / System / Password", "غيّر admin الافتراضي فورًا. هذه كلمة دخول اللوحة وليست كلمة الواي فاي."),
    Triple("⬆️ تحديث الفيرموير", "System / Firmware Upgrade", "يسد الثغرات الأمنية. لا تقطع الكهرباء أثناء التحديث."),
    Triple("🔄 إعادة تشغيل أو ضبط المصنع", "System / Reboot / Factory Reset", "إعادة التشغيل تحل معظم المشاكل. ضبط المصنع يمسح كل الإعدادات، استخدمه كآخر حل.")
)

@Suppress("DEPRECATION")
@Composable
fun RouterScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("router", Context.MODE_PRIVATE) }
    val gw = remember { ip(wifiMgr(ctx).dhcpInfo.gateway).let { if (it == "0.0.0.0") "192.168.1.1" else it } }
    var host by remember { mutableStateOf(prefs.getString("host", null) ?: gw) }
    var https by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf(prefs.getString("user", "admin") ?: "admin") }
    var pass by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    var showPass by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val fillOnce = remember { booleanArrayOf(false) }
    val hostRef = rememberUpdatedState(host)
    val finishCb = rememberUpdatedState {
        if (fillOnce[0]) { fillOnce[0] = false; web?.evaluateJavascript(fillJs(user, pass), null) }
    }

    BackHandler { if (tab == 0 && web?.canGoBack() == true) web?.goBack() else onBack() }

    Column(Modifier.fillMaxSize().background(Color(0xFFF2F5FA))) {
        Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF1565C0), Color(0xFF00ACC1))))
            .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع ›", color = Color.White) }
            Text("لوحة تحكم الراوتر", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("لوحة الراوتر") })
            Tab(tab == 1, { tab = 1 }, text = { Text("دليل الإعدادات") })
        }
        if (tab == 0) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(host, { host = it }, label = { Text("عنوان الراوتر") }, singleLine = true, modifier = Modifier.weight(1f))
                    FilterChip(selected = https, onClick = { https = !https }, label = { Text("HTTPS") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(user, { user = it }, label = { Text("اسم المستخدم") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(pass, { pass = it }, label = { Text("كلمة السر") }, singleLine = true, modifier = Modifier.weight(1f),
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        prefs.edit().putString("host", host).putString("user", user).putString("pass", pass).apply()
                        fillOnce[0] = true
                        web?.loadUrl((if (https) "https://" else "http://") + host.trim())
                    }) { Text("فتح وتسجيل دخول") }
                    OutlinedButton(onClick = { web?.evaluateJavascript(fillJs(user, pass), null) }) { Text("تعبئة") }
                    OutlinedButton(onClick = { web?.reload() }) { Text("تحديث") }
                    TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "إخفاء" else "إظهار") }
                }
                Text("اضغط «فتح وتسجيل دخول» ثم اضغط زر Login في صفحة الراوتر. تأكد أنك متصل بواي فاي الراوتر.", fontSize = 11.sp, color = Color.Gray)
            }
        }
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                            if (Uri.parse(error?.url ?: "").host == hostRef.value.trim()) handler?.proceed() else handler?.cancel()
                        }
                        override fun onPageFinished(view: WebView?, url: String?) { finishCb.value() }
                    }
                }.also { web = it }
            },
            modifier = if (tab == 0) Modifier.fillMaxWidth().weight(1f) else Modifier.size(0.dp)
        )
        if (tab == 1) {
            LazyColumn(Modifier.weight(1f)) {
                item {
                    Card2("قبل ما تبدأ") {
                        Text("• عنوان الراوتر غالبًا $gw (اكتشفه التطبيق تلقائيًا).\n• اسم المستخدم وكلمة السر مكتوبان عادة على ملصق خلف الراوتر، والشائع admin / admin.\n• كل شركة راوترات لها شكل قائمة مختلف، لكن الأسماء التالية متشابهة في معظمها.\n• غيّر كلمة سر الراوتر الافتراضية فورًا.", fontSize = 13.sp)
                    }
                }
                items(guide) { (t, where, what) ->
                    Card2(t) {
                        Text("مكانه: $where", fontSize = 12.sp, color = Color(0xFF1565C0), fontWeight = FontWeight.Medium)
                        Text(what, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
