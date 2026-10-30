package com.kmibrahim.deenorav3

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.media.AudioManager
import android.provider.Settings
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var singlePhotoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var multiPhotoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    // Call active tracking (same-call reload guard)
    private var isCallActive = false
    // Call ringing tracking (v2 fix: ringing obosthay isCallActive=false thake)
    private var isCallRinging = false
    // Native call UI mode: CallActivity jokhon call screen dekhay, tokhon
    // MainActivity sudhu background audio-engine — kono web call UI dekhabe na.
    private var nativeCallUi = false

    private val callActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.getStringExtra("call_action")
            val audioAction = intent?.getStringExtra("audio_action")
            val callId = intent?.getStringExtra("call_id") ?: ""
            val onDeenora = webView.url?.contains("deenora.app") == true

            if (!onDeenora) return

            if (audioAction == "mute_toggle") {
                webView.evaluateJavascript(
                    "window.__nativeCallUi=true;if(typeof window.handleNativeCallAction==='function') window.handleNativeCallAction('mute_toggle','$callId');",
                    null
                )
                return
            }

            when (action) {
                "answer" -> {
                    webView.evaluateJavascript(
                        "window.__nativeCallUi=true; " +
                        "if(typeof window.handleNativeCallAction==='function') { " +
                        "  console.log('Deenora: handleNativeCallAction executing answer'); " +
                        "  window.handleNativeCallAction('answer','$callId'); " +
                        "} else { console.log('Deenora: handleNativeCallAction NOT FOUND'); }",
                        null
                    )
                }
                "decline", "end" -> {
                    webView.evaluateJavascript(
                        "window.__nativeCallUi=true; " +
                        "if(typeof window.handleNativeCallAction==='function') window.handleNativeCallAction('$action','$callId');",
                        null
                    )
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "deenora_downloads"
        private const val TAG = "DeenoraV3"

        fun getOrCreateDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences("deenora_push", Context.MODE_PRIVATE)
            var devId = prefs.getString("deenora_device_id", null)
            if (devId.isNullOrBlank()) {
                devId = java.util.UUID.randomUUID().toString()
                prefs.edit().putString("deenora_device_id", devId).apply()
            }
            return devId
        }
    }

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != -1L) {
                runOnUiThread {
                    Toast.makeText(context, "ডাউনলোড সম্পন্ন হয়েছে। ফাইলটি 'Downloads' ফোল্ডারে দেখুন।", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAndUnlockScreen()

        nativeCallUi = intent.getBooleanExtra("native_ui", false)

        webView = WebView(this)
        setContentView(webView)

        // FCM push token sync
        FcmService.syncToken(this)

        setupLaunchers()
        setupWebView()

        webView.loadUrl(buildStartUrl(intent))

        setupBackButton()
        checkAndRequestPermissions()
        createNotificationChannel()
        requestFullScreenIntentAccessIfNeeded()
        handleCallAction(intent)

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        val callFilter = IntentFilter("com.kmibrahim.deenorav3.WEB_CALL_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, filter, Context.RECEIVER_EXPORTED)
            registerReceiver(callActionReceiver, callFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(onDownloadComplete, filter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(callActionReceiver, callFilter)
        }

        CookieManager.getInstance().flush()

        // Native call UI mode: CallActivity screen e thakbe, MainActivity
        // sudhu background audio-engine — nijeke background e pathano.
        if (nativeCallUi) {
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(onDownloadComplete)
            unregisterReceiver(callActionReceiver)
        } catch (e: Exception) { }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        wakeAndUnlockScreen()

        val isNativeUi = intent.getBooleanExtra("native_ui", false)
        if (isNativeUi) nativeCallUi = true else nativeCallUi = false

        handleCallAction(intent)

        // IMPORTANT: Prevent reload if already on an auth-related page to preserve sessionStorage/state
        val currentUrl = webView.url ?: ""
        if (isAuthFlowUrl(currentUrl)) {
            Log.d(TAG, "Auth flow in progress, skipping reload to preserve sessionStorage")
            return
        }

        // Guard v2: ringing BA active call cholakalin kono tap-e WebView reload kore call katbe na
        if (isCallActive || isCallRinging) {
            Log.d(TAG, "Call ringing/active, skipping reload")
            if (isNativeUi) moveTaskToBack(true)
            return
        }

        val url = buildStartUrl(intent)
        if (url != "https://deenora.app") {
            webView.loadUrl(url)
        }
        if (isNativeUi) moveTaskToBack(true)
    }

    private fun isAuthFlowUrl(url: String): Boolean {
        return url.contains("firebaseapp.com") ||
                url.contains("accounts.google.com") ||
                url.contains("__/auth/") ||
                url.contains("googleid.googleapis.com")
    }

    // ---------- Native answer: WebView background/foreground helpers ----------
    // (Kept for compatibility if needed, but Veil logic is mostly unused now as we use BroadcastReceiver)

    private fun wakeAndUnlockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun buildStartUrl(intent: Intent?): String {
        val extras = intent?.extras ?: return "https://deenora.app"
        val callerName = extras.getString("caller_name")
        val callId = extras.getString("call_id")
        val type = extras.getString("type")

        if (!callerName.isNullOrBlank() || type == "incoming_call") {
            val params = StringBuilder("https://deenora.app?fcm_tap=1")
            if (!callId.isNullOrBlank()) params.append("&call_id=").append(Uri.encode(callId))
            if (!callerName.isNullOrBlank()) params.append("&caller_name=").append(Uri.encode(callerName))
            if (extras.getString("call_action") == "answer") {
                params.append("&answer=1")
            } else if (extras.getString("call_action") == "decline") {
                params.append("&answer=0")
            } else if (extras.getString("call_action") == "end") {
                params.append("&answer=0")
            }
            // Native call UI mode — web jeno kono call modal na dekhay
            if (extras.getBoolean("native_ui", false)) {
                params.append("&native_ui=1")
            }
            return params.toString()
        }
        return "https://deenora.app"
    }

    private fun setupLaunchers() {
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val results = result.data?.let { data ->
                    val clipData = data.clipData
                    if (clipData != null) {
                        Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    } else {
                        data.data?.let { arrayOf(it) }
                    }
                }
                filePathCallback?.onReceiveValue(results)
            } else {
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
        }

        singlePhotoPickerLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            filePathCallback = null
        }

        multiPhotoPickerLauncher = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            filePathCallback?.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
            filePathCallback = null
        }

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }
    }

    private fun setupWebView() {
        applySettings(webView)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("DeenoraWebConsole", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val host = request?.origin?.host ?: ""
                if (request != null && (host == "deenora.app" || host.endsWith(".deenora.app"))) {
                    val allowed = request.resources
                        .filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                        .toTypedArray()
                    if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
                } else {
                    request?.deny()
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                if (intent != null) filePickerLauncher.launch(intent)
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // White Screen Fix: Avoid injecting script on Auth domains or handlers to prevent CSP violations
                if (url != null && url.contains("deenora.app") && !url.contains("__/auth/")) {
                    injectBlobHook(view)
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.contains("deenora.app") && !url.contains("__/auth/")) {
                    injectBlobHook(view)
                    injectDownloadScripts(view)
                    // Native call UI mode — web jeno call modal na dekhay (Always true)
                    view?.evaluateJavascript("window.__nativeCallUi=true; sessionStorage.setItem('deenora_native_ui', '1');", null)
                }
                CookieManager.getInstance().flush()
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: ""

                // Auth URLs MUST stay in the same WebView instance to keep state/session
                if (isAuthFlowUrl(url)) {
                    return false
                }

                if (url.lowercase(Locale.ROOT).contains(".pdf") && !url.startsWith("blob:") && !url.startsWith("data:")) {
                    handleDownload(url, webView.settings.userAgentString, null, "application/pdf")
                    return true
                }
                if (handleExternalUrls(url, view)) return true
                if (!isTrustedUrl(url)) {
                    openInBrowser(url)
                    return true
                }
                return false
            }
        }

        webView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")

        webView.addJavascriptInterface(
            GoogleSignInBridge(this) { idToken ->
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onNativeGoogleToken && window.onNativeGoogleToken(${if (idToken != null) "'$idToken'" else "null"})",
                        null
                    )
                }
            },
            "AndroidGoogleSignIn"
        )

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun applySettings(v: WebView) {
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true

            // Critical Fix: Disable multi-window to force Auth flows into the same frame/session
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true

            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT

            // CLEAN Mobile UA: Standard Chrome Mobile version without "wv" or "Version/X" markers.
            // This bypasses Google's "Embedded" block while maintaining session integrity.
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.164 Mobile Safari/537.36"
        }
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(v, true)
    }

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            webView.evaluateJavascript("if(typeof window.triggerDownload === 'function') window.triggerDownload('$url', '');", null)
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setTitle(fileName)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        } catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (ex: Exception) { }
        }
    }

    private fun saveBase64ToFile(base64Data: String?, fileName: String?, mimeType: String?) {
        if (base64Data.isNullOrEmpty()) return
        try {
            val dataPart = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
            val bytes = Base64.decode(dataPart.trim(), Base64.DEFAULT)
            var cleanFileName = fileName?.ifEmpty { "deenora_file_" + System.currentTimeMillis() } ?: ("file_" + System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) { }
    }

    private fun injectBlobHook(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                if (window.deenoraBlobHooked) return;
                window.deenoraBlobs = window.deenoraBlobs || {};
                var origCreate = URL.createObjectURL;
                URL.createObjectURL = function(obj) {
                    var url = origCreate(obj);
                    if (obj instanceof Blob) window.deenoraBlobs[url] = obj;
                    return url;
                };
            })();
        """.trimIndent(), null)
    }

    private fun injectDownloadScripts(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                if (window.deenoraScriptsLoaded) return;
                window.deenoraScriptsLoaded = true;
                window.triggerDownload = function(url, filename) {
                    if (!url) return;
                    if (url.startsWith('blob:')) {
                        var blob = (window.deenoraBlobs && window.deenoraBlobs[url]) || null;
                        if (blob) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                AndroidInterface.downloadFile(reader.result, filename || 'document', blob.type || 'application/pdf');
                            };
                            reader.readAsDataURL(blob);
                        }
                    }
                };
            })();
        """.trimIndent(), null)
    }

    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_DEFAULT)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun isTrustedUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return true
        if (scheme != "http" && scheme != "https") return true
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false

        val trusted = listOf(
            "deenora.app",
            "google.com",
            "firebaseapp.com",
            "googleid.googleapis.com",
            "gstatic.com",
            "googleusercontent.com",
            "firebase.com",
            "supabase.co"
        )
        return trusted.any { host == it || host.endsWith(".$it") }
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    private fun handleCallAction(intent: Intent?) {
        if (intent?.getStringExtra("call_action") == "answer") {
            CallService.stop(this)
            OngoingCallService.start(this)
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        } else if (intent?.getStringExtra("call_action") == "decline") {
            CallService.stop(this)
            // Decline hole ongoing service o bondho (age miss chilo)
            OngoingCallService.stop(this)
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        } else if (intent?.getStringExtra("call_action") == "end") {
            CallService.stop(this)
            OngoingCallService.stop(this)
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        }
    }

    private fun requestFullScreenIntentAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            val prefs = getSharedPreferences("deenora_push", Context.MODE_PRIVATE)
            if (!nm.canUseFullScreenIntent() && !prefs.getBoolean("fsi_prompted", false)) {
                prefs.edit().putBoolean("fsi_prompted", true).apply()
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) { }
            }
        }
    }

    private fun handleExternalUrls(url: String, view: WebView?): Boolean {
        if (url.contains("youtube.com") || url.contains("youtu.be") || url.startsWith("vnd.youtube:")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                return true
            } catch (e: Exception) {
                return false
            }
        }
        if (url.startsWith("intent://")) {
            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                if (intent != null) {
                    val info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    if (info != null) {
                        startActivity(intent)
                    } else {
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        if (fallbackUrl != null) {
                            view?.loadUrl(fallbackUrl)
                        }
                    }
                    return true
                }
            } catch (e: Exception) { }
            return true
        }
        if (url.contains("wa.me") || url.startsWith("whatsapp:") ||
            url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                return true
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun stopRinging() {
            this@MainActivity.isCallRinging = false
            CallService.stop(this@MainActivity)
        }

        @JavascriptInterface
        fun onIncomingCall(callerName: String, callId: String) {
            this@MainActivity.isCallActive = false
            this@MainActivity.isCallRinging = true
            runOnUiThread {
                Log.d(TAG, "Web triggered incoming call: $callerName ($callId)")
                // CallService.stop(this@MainActivity) - REMOVED: Do not interrupt ringing
                CallService.start(this@MainActivity, callerName, callId)
            }
        }

        @JavascriptInterface
        fun callStarted() {
            this@MainActivity.isCallActive = true
            this@MainActivity.isCallRinging = false
            runOnUiThread {
                CallService.stop(this@MainActivity)
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                OngoingCallService.start(this@MainActivity)
            }
        }

        @JavascriptInterface
        fun callEnded() {
            this@MainActivity.isCallActive = false
            this@MainActivity.isCallRinging = false
            runOnUiThread {
                OngoingCallService.stop(this@MainActivity)
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_NORMAL
                // Admin katle native call screen o bondho hobe
                try {
                    sendBroadcast(
                        Intent(CallActivity.ACTION_CALL_DISMISSED).setPackage(packageName)
                    )
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun downloadFile(base64: String, name: String, mime: String) {
            saveBase64ToFile(base64, name, mime)
        }

        @JavascriptInterface
        fun getDeviceId(): String {
            return getOrCreateDeviceId(this@MainActivity)
        }

        @JavascriptInterface
        fun savePushIdentity(phone: String?, studentId: String?, institutionId: String?) {
            val prefs = getSharedPreferences("deenora_push", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (!phone.isNullOrBlank()) {
                editor.putString("parent_phone", phone)
            }
            if (!studentId.isNullOrBlank()) {
                editor.putString("parent_student_id", studentId)
            }
            if (!institutionId.isNullOrBlank()) {
                editor.putString("parent_institution_id", institutionId)
            }
            editor.apply()
        }

        @JavascriptInterface
        fun getFcmToken(): String {
            return getSharedPreferences("deenora_push", Context.MODE_PRIVATE).getString("fcm_token", "") ?: ""
        }

        /** Web jante parbe native call UI active kina (parent app e web modal lukabe). */
        @JavascriptInterface
        fun isNativeCallUi(): Boolean {
            return true // Always return true so web NEVER shows its own call modal
        }
    }
}
