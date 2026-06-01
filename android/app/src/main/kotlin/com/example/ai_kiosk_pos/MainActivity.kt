package com.example.ai_kiosk_pos

import com.example.ai_kiosk_pos.printer.PrinterManager

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.Cancelable
import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.callable.TapToPayReaderListener
import com.stripe.stripeterminal.external.callable.TerminalListener
import com.stripe.stripeterminal.external.models.CollectPaymentIntentConfiguration
import com.stripe.stripeterminal.external.models.DisconnectReason
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.ConnectionStatus
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import com.stripe.stripeterminal.external.models.DeviceType
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.PaymentStatus
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.log.LogLevel
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator

/**
 * MainActivity for AI Kiosk POS — Stripe Terminal SDK 5.2.0 (Tap to Pay)
 *
 * Production-quality implementation with:
 * - Thread-safe state management (synchronized result delivery)
 * - Proper Cancelable lifecycle (cancel before null — no ghost callbacks)
 * - Tracked Handler runnables (no stale timeouts across payment attempts)
 * - No overlapping discoveries (prewarmup guards against eagerPrepare)
 * - Clean error recovery on every code path
 * - cancelPayment() callable from Flutter
 */
class MainActivity : FlutterActivity(), TerminalListener, TapToPayReaderListener {

  companion object {
    private const val TAG = "StripeTerminal"
    private const val CHANNEL_NAME = "kiosk.stripe.terminal"
    private const val STRIPE_SDK_VERSION = "5.2.0"
    private const val TAP_TO_PAY_MIN_ANDROID_API = 33
    private const val MAX_SECURITY_PATCH_AGE_MONTHS = 12L
    private const val REQUIRED_HARDWARE_KEYSTORE_VERSION = 100
    private const val PAYMENT_TIMEOUT_MS = 120_000L        // 2 minutes
    private const val DISCOVERY_TIMEOUT_MS = 15_000L       // 15 seconds
    private const val READER_WAIT_POLL_MS = 300L           // poll interval
    private const val READER_WAIT_TIMEOUT_MS = 15_000L     // max wait for in-progress connect
    private const val CONNECT_MAX_RETRIES = 2
    private const val CONNECT_INITIAL_DELAY_MS = 500L
    private const val PREWARMUP_DURATION_MS = 2_000L       // 2 seconds
    private const val TOKEN_FETCH_RETRY_DELAY_MS = 800L
  }

  private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val mainHandler = Handler(Looper.getMainLooper())

  // ═══════════════════════════════════════════════════════════
  // HTTP Client
  // ═══════════════════════════════════════════════════════════

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

  // ═══════════════════════════════════════════════════════════
  // Token Provider (deferred — URL set later)
  // ═══════════════════════════════════════════════════════════

  @Volatile private var tokenProviderUrl: String? = null

  private val deferredTokenProvider = object : ConnectionTokenProvider {
    override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
      val url = tokenProviderUrl
      if (url == null) {
        callback.onFailure(ConnectionTokenException("Terminal base URL not configured yet"))
        return
      }
      activityScope.launch(Dispatchers.IO) {
        try {
          sendDebugLog("🔑 Fetching connection token...")
          val secret = fetchConnectionTokenFromBackend(url)
          sendDebugLog("✅ Connection token fetched")
          withContext(Dispatchers.Main) { callback.onSuccess(secret) }
        } catch (e: Exception) {
          Log.e(TAG, "Connection token fetch failed: ${e.message}", e)
          sendDebugLog("❌ Connection token fetch failed: ${e.message}")
          withContext(Dispatchers.Main) {
            callback.onFailure(ConnectionTokenException(e.message ?: "Token fetch failed", e))
          }
        }
      }
    }
  }

  // ═══════════════════════════════════════════════════════════
  // Thread-safe State Management
  // ═══════════════════════════════════════════════════════════

  // Result delivery — synchronized to prevent double-delivery crash
  private val resultLock = Any()
  private var pendingResult: MethodChannel.Result? = null

  // Payment guard — only one payment at a time
  private val isProcessing = AtomicBoolean(false)

  // Reader connection state
  private val isConnectingReader = AtomicBoolean(false)

  // Active SDK operations — MUST be cancelled before nulling
  @Volatile private var discoveryCancelable: Cancelable? = null
  @Volatile private var currentPaymentCancelable: Cancelable? = null

  // Whether the TTP NFC screen is showing (used by onUserLeaveHint)
  @Volatile private var ttpActivityLaunched = false

  // Tracked handler runnables — cancelled on resetState() and onDestroy()
  // to prevent stale callbacks from firing across payment attempts
  private val trackedRunnables = mutableSetOf<Runnable>()
  private val runnablesLock = Any()

  private var terminalBaseUrl: String? = null
  private var methodChannel: MethodChannel? = null

  // ═══════════════════════════════════════════════════════════
  // Printer Manager
  // ═══════════════════════════════════════════════════════════

  private lateinit var printerManager: PrinterManager

  // ═══════════════════════════════════════════════════════════
  // Permission State
  // ═══════════════════════════════════════════════════════════

  private var pendingPermissionGranted: (() -> Unit)? = null
  private var pendingPermissionDenied: (() -> Unit)? = null
  private var pendingMicrophoneResult: MethodChannel.Result? = null
  private var pendingBluetoothPermissionResult: MethodChannel.Result? = null
  private var eagerPrepareConfig: Triple<String, String, Boolean>? = null

  private val locationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
  )
  private val locationPermissionRequestCode = 1001
  private val microphonePermissionRequestCode = 1002
  private val eagerPermissionRequestCode = 1003
  private val bluetoothPermissionRequestCode = 1004

  // ═══════════════════════════════════════════════════════════
  // LIFECYCLE
  // ═══════════════════════════════════════════════════════════

  override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)

    val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
    methodChannel = channel

    // Initialize PrinterManager
    printerManager = PrinterManager(this)
    printerManager.debugLogSender = { msg -> sendDebugLog(msg) }
    printerManager.statusSender = { status ->
      methodChannel?.invokeMethod("onPrinterStatusChanged", status)
    }

    channel.setMethodCallHandler { call, result ->
      val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
      when (call.method) {
        "startTapToPay"   -> startTapToPay(args, result)
        "prepareTapToPay" -> prepareTapToPay(args, result)
        "cancelPayment"   -> cancelPayment(result)
        "requestMicrophonePermission" -> requestMicrophonePermission(result)
        "getNfcStatus"    -> getNfcStatus(result)
        "getLocationStatus" -> getLocationStatus(result)
        "getBluetoothStatus" -> getBluetoothStatus(result)
        "requestBluetoothPermissions" -> requestBluetoothPermissions(result)
        "getDeveloperOptionsStatus" -> getDeveloperOptionsStatus(result)
        "openNfcSettings" -> { openNfcSettings(); result.success(true) }
        "openLocationSettings" -> { openLocationSettings(); result.success(true) }
        "openBluetoothSettings" -> { openBluetoothSettings(); result.success(true) }
        "openAppSettings" -> { openAppSettings(); result.success(true) }
        "openUsbSettings" -> { openUsbSettings(); result.success(true) }
        "openDeveloperSettings" -> { openDeveloperSettings(); result.success(true) }
        "prewarmupNfc"    -> prewarmupNfc(args, result)
        "eagerPrepare"    -> eagerPrepare(args, result)
        "getDeviceInfo"   -> getDeviceInfo(result)

        // ── Printer Operations ──
        "scanPrinters"        -> printerManager.scanPrinters(result)
        "connectPrinter"      -> printerManager.connectPrinter(args, result)
        "disconnectPrinter"   -> printerManager.disconnectPrinter(result)
        "getPrinterStatus"    -> printerManager.getPrinterStatus(result)
        "printReceipt"        -> printerManager.printReceipt(args, result)
        "printKot"            -> printerManager.printKot(args, result)
        "printReport"         -> printerManager.printReport(args, result)
        "testPrint"           -> printerManager.testPrint(result)
        "printRaw"            -> printerManager.printRaw(args, result)
        "updatePrinterSettings" -> printerManager.updateSettings(args, result)

        else              -> result.notImplemented()
      }
    }

    // Pre-initialize Terminal SDK at startup (deferred token provider — no URL needed yet)
    initializeTerminalSdk()

    // Auto-reconnect to last used printer in background
    printerManager.autoReconnectLastPrinter()
  }

  /**
   * Pre-request all permissions at Activity startup for maximum payment speed.
   * By the time the user hits "Pay", permissions are already granted.
   */
  override fun onStart() {
    super.onStart()
    val needed = mutableListOf<String>()
    locationPermissions.forEach { perm ->
      if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
        needed.add(perm)
      }
    }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      needed.add(Manifest.permission.RECORD_AUDIO)
    }
    // Bluetooth permissions required on Android 12+ (API 31+) for printer scanning
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        needed.add(Manifest.permission.BLUETOOTH_CONNECT)
      }
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
        needed.add(Manifest.permission.BLUETOOTH_SCAN)
      }
    }
    if (needed.isNotEmpty()) {
      Log.d(TAG, "Pre-requesting ${needed.size} permission(s) at startup")
      ActivityCompat.requestPermissions(this, needed.toTypedArray(), eagerPermissionRequestCode)
    }
  }

  override fun onResume() {
    super.onResume()
    if (::printerManager.isInitialized) {
      printerManager.handleAppResume()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    // Cancel ALL tracked runnables to prevent callbacks on dead activity
    cancelAllTrackedRunnables()
    // Cancel any active SDK operations
    safeCancel(discoveryCancelable)
    safeCancel(currentPaymentCancelable)
    // Shutdown coroutine scope and HTTP client
    if (::printerManager.isInitialized) {
      printerManager.shutdown()
    }
    activityScope.cancel()
    httpClient.dispatcher.executorService.shutdown()
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    // Don't interfere if TTP NFC screen is showing (user is tapping card)
    if (ttpActivityLaunched) return
    // Don't interfere if no payment is processing
    if (!isProcessing.get()) return

    // Try to cancel the active payment operation.
    // Either the cancel callback or the payment callback will deliver the result — never both.
    val cancelable = currentPaymentCancelable
    if (cancelable != null && !cancelable.isCompleted) {
      cancelable.cancel(object : Callback {
        override fun onSuccess() {
          // Cancel succeeded → SDK won't fire the payment callback, we deliver
          finishWithError("PAYMENT_CANCELLED", "Payment cancelled — app minimized", null)
        }
        override fun onFailure(e: TerminalException) {
          // Cancel failed → payment is already completing, let its callback deliver
          Log.d(TAG, "Cancel on minimize failed (payment completing): ${e.message}")
        }
      })
    } else if (currentPaymentCancelable == null) {
      // No active collect → still in discover/connect phase, safe to cancel directly
      finishWithError("PAYMENT_CANCELLED", "Payment cancelled — app minimized", null)
    }
  }

  // ═══════════════════════════════════════════════════════════
  // TERMINAL INITIALIZATION
  // ═══════════════════════════════════════════════════════════

  private fun initializeTerminalSdk() {
    if (Terminal.isInitialized()) return

    try {
      Log.i(TAG, "Initializing Terminal SDK on ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
      sendDebugLog("🔧 Initializing Terminal SDK on ${Build.MANUFACTURER} ${Build.MODEL}")

      val logLevel = if (BuildConfig.DEBUG) LogLevel.VERBOSE else LogLevel.NONE
      Terminal.init(applicationContext, logLevel, deferredTokenProvider, this, null)

      Log.d(TAG, "Terminal pre-initialized at startup ✅")
      sendDebugLog("✅ Terminal pre-initialized successfully")

      // Run diagnostics in background (non-blocking)
      activityScope.launch(Dispatchers.IO) { logDeviceSecurityCapabilities() }
      activityScope.launch { logTtpSupportInfo() }

    } catch (e: Exception) {
      handleTerminalInitError(e)
    }
  }

  private fun logTtpSupportInfo() {
    try {
      val ttpResult = Terminal.getInstance().supportsReadersOfType(
        DeviceType.TAP_TO_PAY_DEVICE,
        DiscoveryConfiguration.TapToPayDiscoveryConfiguration(false)
      )
      Log.i(TAG, "supportsReadersOfType: $ttpResult")
      sendDebugLog("📱 Tap to Pay support check: $ttpResult")
    } catch (e: Exception) {
      Log.w(TAG, "supportsReadersOfType failed: ${e.message}")
      sendDebugLog("⚠️ TTP support check failed: ${e.message}")
    }

    val secPatch = Build.VERSION.SECURITY_PATCH
    val devOpts = try {
      Settings.Global.getInt(contentResolver, "development_settings_enabled", 0) != 0
    } catch (_: Exception) { false }

    val patchStatus = when {
      getSecurityPatchDate() == null -> "unknown"
      getSecurityPatchError() == null -> "✅ recent"
      else -> "⚠️ outdated"
    }
    val hardwareKeystoreStatus = when (val supported = hasRequiredHardwareKeystoreFeature()) {
      true -> "✅ v$REQUIRED_HARDWARE_KEYSTORE_VERSION+"
      false -> "⚠️ missing/undetected"
      null -> "unknown"
    }

    Log.i(TAG, "TTP info: secPatch=$secPatch, devOptions=$devOpts, debug=${BuildConfig.DEBUG}")
    sendDebugLog(
      "ℹ️ Patch: $secPatch ($patchStatus) | DevOpts: ${if (devOpts) "⚠️ON" else "✅OFF"} | HWKeyStore: $hardwareKeystoreStatus | SDK: $STRIPE_SDK_VERSION"
    )
    if (devOpts) {
      sendDebugLog("⚠️ Developer options enabled — production Tap to Pay is blocked on SDK $STRIPE_SDK_VERSION")
    }
    if (getSecurityPatchError() != null) {
      sendDebugLog("⚠️ Security patch is older than the last 12 months — Tap to Pay will be blocked")
    }
  }

  private fun handleTerminalInitError(e: Exception) {
    Log.e(TAG, "Terminal initialization failed: ${e.javaClass.simpleName}: ${e.message}")

    val errorMsg = e.message?.lowercase() ?: ""
    val isSunmiDevice = Build.MANUFACTURER.equals("SUNMI", ignoreCase = true)
    val isHardwareError = errorMsg.contains("tee") || errorMsg.contains("hardware") ||
        errorMsg.contains("attestation") || errorMsg.contains("strongbox")

    if (isHardwareError && isSunmiDevice) {
      Log.w(TAG, "SUNMI ${Build.MODEL}: Hardware security init issue (usually resolves on retry)")
      sendDebugLog("⚠️ Terminal init: hardware-backed keystore setup issue — will retry on first payment")
      sendDebugLog("ℹ️ SUNMI ${Build.MODEL} must meet Android 13, recent patch, and hardware keystore requirements on SDK $STRIPE_SDK_VERSION")
    } else if (isHardwareError) {
      Log.w(TAG, "Device ${Build.MANUFACTURER} ${Build.MODEL}: Missing Tap to Pay hardware requirements")
      sendDebugLog("⚠️ Terminal init failed: device may not meet Tap to Pay hardware requirements")
      sendDebugLog("ℹ️ Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
    } else {
      sendDebugLog("❌ Terminal init failed: ${e.message}")
    }
  }

  /**
   * Ensure Terminal SDK is initialized with the given URL.
   * Terminal is pre-initialized at startup; this just sets/updates the token provider URL.
   */
  private fun ensureTerminalInitialized(url: String, onReady: () -> Unit) {
    tokenProviderUrl = url
    if (!Terminal.isInitialized()) {
      try {
        val logLevel = if (BuildConfig.DEBUG) LogLevel.VERBOSE else LogLevel.NONE
        Terminal.init(applicationContext, logLevel, deferredTokenProvider, this, null)
        Log.d(TAG, "Terminal initialized (late init)")
        sendDebugLog("✅ Terminal initialized (late init)")
      } catch (e: Exception) {
        Log.e(TAG, "Terminal late-init failed: ${e.javaClass.simpleName}: ${e.message}")
        sendDebugLog("⚠️ Terminal late-init failed: ${e.message}")
      }
    }
    onReady()
  }

  // ═══════════════════════════════════════════════════════════
  // PAYMENT: startTapToPay
  // ═══════════════════════════════════════════════════════════

  private fun startTapToPay(args: Map<*, *>, result: MethodChannel.Result) {
    // Guard: only one payment at a time
    if (isProcessing.getAndSet(true)) {
      return result.error("BUSY", "Payment already in progress", null)
    }

    synchronized(resultLock) { pendingResult = result }
    schedulePaymentTimeout()

    sendDebugLog("💳 Starting Tap to Pay payment")

    val secret = args["clientSecret"] as? String
    val locId = args["locationId"] as? String
    val url = args["terminalBaseUrl"] as? String
    val orderId = args["orderId"] as? String
    val isSim = args["isSimulated"] as? Boolean ?: false

    // Device capability check (skip developer options check in simulated mode)
    checkDeviceCapability(isSim)?.let { (code, msg) ->
      sendDebugLog("❌ Device capability check failed: $msg")
      return finishWithError(code, msg, null)
    }

    if (secret == null || url == null || locId == null) {
      sendDebugLog("❌ Missing required parameters")
      return finishWithError("INVALID_ARGS", "Missing params", null)
    }

    val nUrl = normalizeBaseUrl(url)
    terminalBaseUrl = nUrl

    ensureTerminalInitialized(nUrl) {
      checkTerminalCapability(isSim)?.let { (code, msg) ->
        sendDebugLog("❌ Terminal capability check failed: $msg")
        finishWithError(code, msg, null)
        return@ensureTerminalInitialized
      }

      val terminal = Terminal.getInstance()

      // FAST PATH: Reader already connected → just retrieve and process
      if (terminal.connectedReader != null) {
        sendDebugLog("✅ Using existing reader connection")
        retrieveAndProcess(secret, orderId)
        return@ensureTerminalInitialized
      }

      sendDebugLog("🔍 Discovering reader...")
      ensureReaderConnected(locId, isSim, { _ ->
        retrieveAndProcess(secret, orderId)
      }, { c, m ->
        finishWithError(c, m, null)
      })
    }
  }

  // ═══════════════════════════════════════════════════════════
  // READER DISCOVERY & CONNECTION
  // ═══════════════════════════════════════════════════════════

  /**
   * Ensure a reader is connected. Either uses an already-connected reader,
   * waits for an in-progress connection, or starts a new discovery+connect cycle.
   */
  private fun ensureReaderConnected(
    locId: String, isSim: Boolean,
    onConn: (Reader) -> Unit, onErr: (String, String) -> Unit
  ) {
    val terminal = Terminal.getInstance()

    // Already connected → return immediately
    terminal.connectedReader?.let { return onConn(it) }

    ensureLocationPermission(
      onGranted = {
        if (!isLocationServicesEnabled()) {
          return@ensureLocationPermission onErr("LOCATION_ERROR", "Location services disabled")
        }

        // If another connection attempt is in progress, wait for it
        if (isConnectingReader.get()) {
          awaitReaderConnection(
            onConnected = { onConn(it) },
            onTimeout = { onErr("CONNECT_FAILED", "Reader connection timed out. Please retry.") }
          )
          return@ensureLocationPermission
        }
        isConnectingReader.set(true)

        // Cancel any leftover discovery from a previous attempt (critical fix)
        safeCancel(discoveryCancelable)
        discoveryCancelable = null

        var readerFoundAndConnecting = false

        val config = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(isSim)
        discoveryCancelable = terminal.discoverReaders(config, object : DiscoveryListener {
          override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
            val reader = readers.firstOrNull()
            if (reader != null && !readerFoundAndConnecting) {
              readerFoundAndConnecting = true
              // Cancel discovery, then connect to the found reader
              val dc = discoveryCancelable
              if (dc != null && !dc.isCompleted) {
                dc.cancel(object : Callback {
                  override fun onSuccess() {
                    discoveryCancelable = null
                    connectToReader(terminal, reader, locId, onConn, onErr)
                  }
                  override fun onFailure(e: TerminalException) {
                    discoveryCancelable = null
                    // Try to connect anyway
                    connectToReader(terminal, reader, locId, onConn, onErr)
                  }
                })
              } else {
                discoveryCancelable = null
                connectToReader(terminal, reader, locId, onConn, onErr)
              }
            }
          }
        }, object : Callback {
          override fun onSuccess() {
            // Discovery completed naturally. If no reader was found, report error.
            if (!readerFoundAndConnecting) {
              isConnectingReader.set(false)
              discoveryCancelable = null
              onErr("NO_READER_FOUND", "No payment reader found. Please try again.")
            }
          }
          override fun onFailure(e: TerminalException) {
            isConnectingReader.set(false)
            discoveryCancelable = null
            Log.e(TAG, "Discovery failed: code=${e.errorCode}, msg=${e.errorMessage}")
            sendDebugLog("❌ Discovery failed [${e.errorCode}]: ${e.errorMessage}")
            onErr("DISCOVERY_FAILED", "[${e.errorCode}] ${e.errorMessage ?: "Discovery failed"}")
          }
        })

        // Safety timeout: cancel discovery if it hangs (tracked for cleanup)
        val timeoutRunnable = Runnable {
          if (isConnectingReader.get() && !readerFoundAndConnecting) {
            Log.w(TAG, "Discovery timeout after ${DISCOVERY_TIMEOUT_MS}ms")
            sendDebugLog("⏱️ Discovery timed out")
            safeCancel(discoveryCancelable)
            isConnectingReader.set(false)
            discoveryCancelable = null
            onErr("DISCOVERY_TIMEOUT", "Reader discovery timed out. Please try again.")
          }
        }
        postTrackedDelayed(timeoutRunnable, DISCOVERY_TIMEOUT_MS)
      },
      onDenied = { onErr("LOCATION_ERROR", "Location permission denied") }
    )
  }

  /**
   * Connect to a discovered reader.
   * Extracted to avoid code duplication between discovery callback branches.
   */
  private fun connectToReader(
    terminal: Terminal, reader: Reader, locId: String,
    onConn: (Reader) -> Unit, onErr: (String, String) -> Unit
  ) {
    terminal.connectedReader?.let {
      isConnectingReader.set(false)
      onConn(it)
      return
    }

    val config = ConnectionConfiguration.TapToPayConnectionConfiguration(
      locId,
      autoReconnectOnUnexpectedDisconnect = true,
      tapToPayReaderListener = this@MainActivity
    )
    terminal.connectReader(reader, config, object : ReaderCallback {
      override fun onSuccess(connectedReader: Reader) {
        isConnectingReader.set(false)
        onConn(connectedReader)
      }
      override fun onFailure(e: TerminalException) {
        if (shouldTreatAsAlreadyConnected(e)) {
          terminal.connectedReader?.let { connected ->
            isConnectingReader.set(false)
            sendDebugLog("ℹ️ Reader already connected — reusing existing connection")
            onConn(connected)
            return
          }
        }
        isConnectingReader.set(false)
        Log.e(TAG, "Connect failed: code=${e.errorCode}, msg=${e.errorMessage}")
        sendDebugLog("❌ Reader connect failed [${e.errorCode}]: ${e.errorMessage}")
        onErr("CONNECT_FAILED", "[${e.errorCode}] ${e.errorMessage ?: "Connect failed"}")
      }
    })
  }

  // ═══════════════════════════════════════════════════════════
  // PAYMENT PROCESSING
  // ═══════════════════════════════════════════════════════════

  /**
   * Retrieve payment intent then collect + confirm.
   * Used when reader is already connected (fast path).
   */
  private fun retrieveAndProcess(secret: String, orderId: String?) {
    sendDebugLog("📥 Retrieving payment intent...")
    Terminal.getInstance().retrievePaymentIntent(secret, object : PaymentIntentCallback {
      override fun onSuccess(intent: PaymentIntent) {
        sendDebugLog("✅ Payment intent retrieved")
        collectAndConfirm(intent, orderId)
      }
      override fun onFailure(e: TerminalException) {
        sendDebugLog("❌ Failed to retrieve payment intent: ${e.errorMessage}")
        finishWithError("RETRIEVE_FAILED", e.errorMessage ?: "Retrieve failed", e.toString())
      }
    })
  }

  /**
   * Collect payment method (shows NFC screen) then confirm.
   * Used by both the fast path and the parallel path.
   */
  private fun collectAndConfirm(intent: PaymentIntent, orderId: String?) {
    sendDebugLog("💳 Collecting payment method...")
    val terminal = Terminal.getInstance()
    ttpActivityLaunched = true

    val config = CollectPaymentIntentConfiguration.Builder().build()
    currentPaymentCancelable = terminal.collectPaymentMethod(intent, object : PaymentIntentCallback {
      override fun onSuccess(collected: PaymentIntent) {
        sendDebugLog("✅ Payment method collected")
        currentPaymentCancelable = null

        sendDebugLog("🔄 Confirming payment...")
        terminal.confirmPaymentIntent(collected, object : PaymentIntentCallback {
          override fun onSuccess(processed: PaymentIntent) {
            sendDebugLog("✅ Payment confirmed successfully!")
            finishWithSuccess(mapOf(
              "status" to "SUCCESS",
              "paymentIntentId" to processed.id,
              "amount" to processed.amount,
              "currency" to processed.currency,
              "orderId" to orderId
            ))
          }
          override fun onFailure(e: TerminalException) {
            sendDebugLog("❌ Payment confirmation failed: ${e.errorMessage}")
            finishWithError("PROCESS_FAILED", e.errorMessage ?: "Process failed", e.toString())
          }
        })
      }
      override fun onFailure(e: TerminalException) {
        sendDebugLog("❌ Payment collection failed: ${e.errorMessage}")
        currentPaymentCancelable = null
        finishWithError("COLLECT_FAILED", e.errorMessage ?: "Collect failed", e.toString())
      }
    }, config)
  }

  // ═══════════════════════════════════════════════════════════
  // STATE: Thread-safe Result Delivery
  // ═══════════════════════════════════════════════════════════

  /**
   * Atomically grab and clear pendingResult.
   * Only the FIRST caller gets the result; subsequent callers get null.
   * Prevents "Reply already submitted" crash from double-delivery.
   */
  private fun takePendingResult(): MethodChannel.Result? {
    synchronized(resultLock) {
      val r = pendingResult
      pendingResult = null
      return r
    }
  }

  private fun finishWithSuccess(map: Map<String, Any?>) {
    val r = takePendingResult()
    resetState()
    mainHandler.post { r?.success(map) }
  }

  private fun finishWithError(code: String, msg: String, det: String?) {
    val r = takePendingResult()
    resetState()
    mainHandler.post { r?.error(code, msg, det) }
  }

  /**
   * Reset ALL payment state.
   *
   * CRITICAL: Cancels active SDK operations BEFORE nulling references.
   * This prevents ghost callbacks from firing on stale state and causing
   * ANR or crashes on subsequent payment attempts.
   *
   * Also cancels all tracked Handler runnables (timeouts, pollers, retries)
   * so stale callbacks from previous payments never fire.
   */
  private fun resetState() {
    // 1. Cancel all tracked runnables (timeouts, pollers, retries)
    cancelAllTrackedRunnables()

    // 2. Cancel active SDK operations BEFORE nulling references
    safeCancel(discoveryCancelable)
    safeCancel(currentPaymentCancelable)

    // 3. Reset all state flags
    isProcessing.set(false)
    isConnectingReader.set(false)
    ttpActivityLaunched = false
    discoveryCancelable = null
    currentPaymentCancelable = null
  }

  // ═══════════════════════════════════════════════════════════
  // CANCELABLE LIFECYCLE
  // ═══════════════════════════════════════════════════════════

  /**
   * Safely cancel a Cancelable, catching all exceptions.
   * Must be called BEFORE setting the reference to null.
   */
  private fun safeCancel(cancelable: Cancelable?) {
    if (cancelable == null || cancelable.isCompleted) return
    try {
      cancelable.cancel(object : Callback {
        override fun onSuccess() {
          Log.d(TAG, "SDK operation cancelled successfully")
        }
        override fun onFailure(e: TerminalException) {
          Log.d(TAG, "SDK cancel failed (already completed): ${e.message}")
        }
      })
    } catch (e: Exception) {
      Log.w(TAG, "Exception cancelling SDK operation: ${e.message}")
    }
  }

  // ═══════════════════════════════════════════════════════════
  // TRACKED HANDLER RUNNABLES
  // ═══════════════════════════════════════════════════════════

  /**
   * Post a delayed runnable that is tracked for cleanup.
   * All tracked runnables are cancelled on resetState() and onDestroy().
   * This prevents stale timeouts from previous payments from firing.
   */
  private fun postTrackedDelayed(runnable: Runnable, delayMs: Long) {
    synchronized(runnablesLock) { trackedRunnables.add(runnable) }
    mainHandler.postDelayed(runnable, delayMs)
  }

  /**
   * Cancel and remove all tracked runnables from the handler queue.
   */
  private fun cancelAllTrackedRunnables() {
    synchronized(runnablesLock) {
      for (r in trackedRunnables) {
        mainHandler.removeCallbacks(r)
      }
      trackedRunnables.clear()
    }
  }

  /**
   * Schedule the overall payment timeout. Uses tracked runnables
   * so it's automatically cancelled on resetState().
   */
  private fun schedulePaymentTimeout() {
    val timeoutRunnable = Runnable {
      if (isProcessing.get()) {
        Log.w(TAG, "Payment timeout after ${PAYMENT_TIMEOUT_MS}ms")
        sendDebugLog("⏱️ Payment timed out")
        finishWithError("TIMEOUT", "Payment timed out", null)
      }
    }
    postTrackedDelayed(timeoutRunnable, PAYMENT_TIMEOUT_MS)
  }

  // ═══════════════════════════════════════════════════════════
  // CANCEL PAYMENT (callable from Flutter)
  // ═══════════════════════════════════════════════════════════

  /**
   * Cancel an in-progress payment. Can be called from Flutter
   * via MethodChannel to allow user-initiated cancellation.
   */
  private fun cancelPayment(result: MethodChannel.Result) {
    if (!isProcessing.get()) {
      result.success(mapOf("status" to "NO_ACTIVE_PAYMENT"))
      return
    }

    sendDebugLog("🛑 Cancel payment requested")

    val cancelable = currentPaymentCancelable
    if (cancelable != null && !cancelable.isCompleted) {
      cancelable.cancel(object : Callback {
        override fun onSuccess() {
          finishWithError("PAYMENT_CANCELLED", "Payment cancelled by user", null)
          result.success(mapOf("status" to "CANCELLED"))
        }
        override fun onFailure(e: TerminalException) {
          // Payment is already completing, can't cancel
          result.success(mapOf("status" to "CANCEL_FAILED", "reason" to (e.message ?: "Already completing")))
        }
      })
    } else {
      // No active collect → still in discover/connect or already done
      finishWithError("PAYMENT_CANCELLED", "Payment cancelled by user", null)
      result.success(mapOf("status" to "CANCELLED"))
    }
  }

  // ═══════════════════════════════════════════════════════════
  // PREPARE TAP TO PAY
  // ═══════════════════════════════════════════════════════════

  private fun prepareTapToPay(args: Map<*, *>, result: MethodChannel.Result) {
    val baseUrl = args["terminalBaseUrl"] as? String
      ?: return result.error("INVALID_ARGS", "No URL", null)
    val locationId = args["locationId"] as? String
      ?: return result.error("INVALID_ARGS", "No LocId", null)
    val isSimulated = args["isSimulated"] as? Boolean ?: BuildConfig.DEBUG

    checkDeviceCapability(isSimulated)?.let { (code, msg) ->
      return result.error(code, msg, null)
    }

    val url = normalizeBaseUrl(baseUrl)
    terminalBaseUrl = url
    sendProgress(0, "Initializing...")

    ensureTerminalInitialized(url) {
      checkTerminalCapability(isSimulated)?.let { (code, msg) ->
        return@ensureTerminalInitialized result.error(code, msg, null)
      }

      val terminal = Terminal.getInstance()

      if (terminal.connectedReader != null) {
        sendProgress(3, "Ready!")
        return@ensureTerminalInitialized result.success(mapOf("status" to "READY"))
      }

      if (isConnectingReader.get()) {
        awaitReaderConnection(
          onConnected = {
            sendProgress(3, "Ready!")
            result.success(mapOf("status" to "READY"))
          },
          onTimeout = {
            result.success(mapOf("status" to "READY", "warning" to "Reader not yet connected"))
          }
        )
        return@ensureTerminalInitialized
      }

      isConnectingReader.set(true)

      ensureLocationPermission(
        onGranted = {
          if (!isLocationServicesEnabled()) {
            isConnectingReader.set(false)
            return@ensureLocationPermission result.error("LOCATION_SERVICES_DISABLED", "Enable Location", null)
          }

          sendProgress(1, "Connecting...")

          // Cancel any leftover discovery (critical fix)
          safeCancel(discoveryCancelable)
          discoveryCancelable = null

          var readerFound = false
          val config = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(isSimulated)

          discoveryCancelable = terminal.discoverReaders(config, object : DiscoveryListener {
            override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
              val reader = readers.firstOrNull()
              if (reader != null && !readerFound) {
                readerFound = true
                val dc = discoveryCancelable
                if (dc != null && !dc.isCompleted) {
                  dc.cancel(object : Callback {
                    override fun onSuccess() {
                      discoveryCancelable = null
                      retryConnectReader(reader, locationId, result, true)
                    }
                    override fun onFailure(e: TerminalException) {
                      discoveryCancelable = null
                      retryConnectReader(reader, locationId, result, true)
                    }
                  })
                } else {
                  discoveryCancelable = null
                  retryConnectReader(reader, locationId, result, true)
                }
              }
            }
          }, object : Callback {
            override fun onSuccess() {}
            override fun onFailure(e: TerminalException) {
              isConnectingReader.set(false)
              discoveryCancelable = null
              result.success(mapOf("status" to "READY", "warning" to "Discovery failed: ${e.message}"))
            }
          })
        },
        onDenied = {
          isConnectingReader.set(false)
          result.error("LOCATION_PERMISSION_DENIED", "Denied", null)
        }
      )
    }
  }

  /**
   * Retry reader connection with exponential backoff.
   * Uses tracked runnables for delay so retries are cancelled on resetState().
   */
  private fun retryConnectReader(
    reader: Reader, locationId: String,
    result: MethodChannel.Result?, isPrepare: Boolean,
    maxRetries: Int = CONNECT_MAX_RETRIES,
    delayMs: Long = CONNECT_INITIAL_DELAY_MS
  ) {
    sendProgress(2, "Downloading...")
    var currentDelay = delayMs
    var attemptCount = 0

    fun attemptConnect() {
      Terminal.getInstance().connectedReader?.let {
        isConnectingReader.set(false)
        sendProgress(3, "Ready!")
        result?.success(mapOf("status" to "READY"))
        return
      }

      attemptCount++
      val config = ConnectionConfiguration.TapToPayConnectionConfiguration(
        locationId,
        autoReconnectOnUnexpectedDisconnect = true,
        tapToPayReaderListener = this@MainActivity
      )

      Terminal.getInstance().connectReader(reader, config, object : ReaderCallback {
        override fun onSuccess(r: Reader) {
          isConnectingReader.set(false)
          sendProgress(3, "Ready!")
          result?.success(mapOf("status" to "READY"))
        }

        override fun onFailure(e: TerminalException) {
          if (shouldTreatAsAlreadyConnected(e)) {
            Terminal.getInstance().connectedReader?.let {
              isConnectingReader.set(false)
              sendProgress(3, "Ready!")
              sendDebugLog("ℹ️ Reader already connected — prepare completed")
              result?.success(mapOf("status" to "READY"))
              return
            }
          }

          if (attemptCount < maxRetries) {
            Log.w(TAG, "Connection attempt $attemptCount failed: ${e.message}, retrying in ${currentDelay}ms")
            val retryRunnable = Runnable {
              currentDelay *= 2
              attemptConnect()
            }
            postTrackedDelayed(retryRunnable, currentDelay)
          } else {
            isConnectingReader.set(false)
            sendProgress(3, "Ready (warning)")
            if (isPrepare) {
              result?.success(mapOf("status" to "READY", "warning" to "Connection retries exhausted: ${e.errorMessage}"))
            } else {
              result?.error("CONNECT_FAILED", "Connection failed after $maxRetries retries: ${e.errorMessage}", null)
            }
          }
        }
      })
    }

    attemptConnect()
  }

  // ═══════════════════════════════════════════════════════════
  // EAGER PREPARE (Background pre-connect at app launch)
  // ═══════════════════════════════════════════════════════════

  /**
   * Eagerly initialize Terminal and connect reader in background.
   * Called from Dart at app startup to minimize first-payment latency.
   * Returns immediately — all heavy work happens asynchronously.
   */
  private fun eagerPrepare(args: Map<*, *>, result: MethodChannel.Result) {
    val baseUrl = args["terminalBaseUrl"] as? String
    val locationId = args["locationId"] as? String
    val isSimulated = args["isSimulated"] as? Boolean ?: false

    if (baseUrl.isNullOrBlank() || locationId.isNullOrBlank()) {
      result.success(mapOf("status" to "SKIPPED", "reason" to "Missing terminalBaseUrl or locationId"))
      return
    }

    checkDeviceCapability(isSimulated)?.let { (code, msg) ->
      result.success(mapOf("status" to "SKIPPED", "reason" to msg, "code" to code))
      return
    }

    val url = normalizeBaseUrl(baseUrl)
    terminalBaseUrl = url

    // Return to Dart immediately — background initialization follows
    result.success(mapOf("status" to "STARTED"))

    ensureTerminalInitialized(url) {
      checkTerminalCapability(isSimulated)?.let { (code, msg) ->
        Log.d(TAG, "EagerPrepare skipped: [$code] $msg")
        sendDebugLog("⚠️ Eager prepare skipped [$code]: $msg")
        return@ensureTerminalInitialized
      }

      val terminal = Terminal.getInstance()

      if (terminal.connectedReader != null) {
        Log.d(TAG, "EagerPrepare: Reader already connected ✅")
        return@ensureTerminalInitialized
      }

      if (locationPermissions.any {
          ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }) {
        Log.d(TAG, "EagerPrepare: Location permission not yet granted, will retry after grant")
        eagerPrepareConfig = Triple(url, locationId, isSimulated)
        return@ensureTerminalInitialized
      }

      if (!isLocationServicesEnabled()) {
        Log.d(TAG, "EagerPrepare: Location services disabled")
        return@ensureTerminalInitialized
      }

      startEagerDiscoveryAndConnect(terminal, locationId, isSimulated)
    }
  }

  /**
   * Background reader discovery and connection for eager initialization.
   */
  private fun startEagerDiscoveryAndConnect(terminal: Terminal, locationId: String, isSimulated: Boolean) {
    if (isConnectingReader.getAndSet(true)) {
      Log.d(TAG, "EagerPrepare: Already connecting, skip")
      return
    }

    Log.d(TAG, "EagerPrepare: Starting discovery...")
    sendProgress(1, "Connecting...")

    // Cancel any leftover discovery (critical fix)
    safeCancel(discoveryCancelable)
    discoveryCancelable = null

    var readerFound = false
    val config = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(isSimulated)

    discoveryCancelable = terminal.discoverReaders(config, object : DiscoveryListener {
      override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
        val reader = readers.firstOrNull()
        if (reader != null && !readerFound) {
          readerFound = true
          val dc = discoveryCancelable
          if (dc != null && !dc.isCompleted) {
            dc.cancel(object : Callback {
              override fun onSuccess() {
                discoveryCancelable = null
                connectEagerReader(terminal, reader, locationId)
              }
              override fun onFailure(e: TerminalException) {
                discoveryCancelable = null
                connectEagerReader(terminal, reader, locationId)
              }
            })
          } else {
            discoveryCancelable = null
            connectEagerReader(terminal, reader, locationId)
          }
        }
      }
    }, object : Callback {
      override fun onSuccess() { /* discovery completed naturally */ }
      override fun onFailure(e: TerminalException) {
        isConnectingReader.set(false)
        discoveryCancelable = null
        Log.w(TAG, "EagerPrepare: Discovery failed: code=${e.errorCode}, msg=${e.errorMessage}")
        sendDebugLog("⚠️ Eager discovery failed [${e.errorCode}]: ${e.errorMessage}")
      }
    })

    // Safety timeout: cancel eager discovery if it hangs (prevents cascading failures)
    val eagerTimeoutRunnable = Runnable {
      if (isConnectingReader.get() && !readerFound) {
        Log.w(TAG, "EagerPrepare: Discovery timeout after 30s")
        sendDebugLog("⚠️ Eager discovery timed out — will retry on payment")
        safeCancel(discoveryCancelable)
        isConnectingReader.set(false)
        discoveryCancelable = null
      }
    }
    postTrackedDelayed(eagerTimeoutRunnable, 30_000L)
  }

  /**
   * Connect to a discovered reader in the eager init background pipeline.
   */
  private fun connectEagerReader(terminal: Terminal, reader: Reader, locationId: String) {
    terminal.connectedReader?.let { connected ->
      isConnectingReader.set(false)
      Log.d(TAG, "EagerPrepare: Reader already connected ✅")
      sendProgress(3, "Ready!")
      sendDebugLog("✅ Reader pre-connected — payments will be instant")
      mainHandler.post {
        methodChannel?.invokeMethod("onReaderConnected", mapOf("source" to "eagerPrepare"))
      }
      return
    }

    Log.d(TAG, "EagerPrepare: Connecting to reader...")
    sendProgress(2, "Downloading...")

    val config = ConnectionConfiguration.TapToPayConnectionConfiguration(
      locationId,
      autoReconnectOnUnexpectedDisconnect = true,
      tapToPayReaderListener = this@MainActivity
    )
    terminal.connectReader(reader, config, object : ReaderCallback {
      override fun onSuccess(r: Reader) {
        isConnectingReader.set(false)
        Log.d(TAG, "EagerPrepare: Reader connected ✅")
        sendProgress(3, "Ready!")
        sendDebugLog("✅ Reader pre-connected — payments will be instant")
        // Notify Flutter so it sets _readerConnected = true
        // This makes the FIRST payment skip the prepare dialog entirely
        mainHandler.post {
          methodChannel?.invokeMethod("onReaderConnected", mapOf("source" to "eagerPrepare"))
        }
      }
      override fun onFailure(e: TerminalException) {
        if (shouldTreatAsAlreadyConnected(e)) {
          terminal.connectedReader?.let { _ ->
            isConnectingReader.set(false)
            Log.d(TAG, "EagerPrepare: Reusing existing reader connection ✅")
            sendProgress(3, "Ready!")
            sendDebugLog("✅ Reader pre-connected — payments will be instant")
            mainHandler.post {
              methodChannel?.invokeMethod("onReaderConnected", mapOf("source" to "eagerPrepare"))
            }
            return
          }
        }

        isConnectingReader.set(false)
        Log.w(TAG, "EagerPrepare: Connect failed: code=${e.errorCode}, msg=${e.errorMessage}")
        sendDebugLog("⚠️ Eager connect failed [${e.errorCode}]: ${e.errorMessage}")
      }
    })
  }

  // ═══════════════════════════════════════════════════════════
  // AWAIT READER CONNECTION (Tracked poller)
  // ═══════════════════════════════════════════════════════════

  /**
   * Wait for an in-progress reader connection to complete.
   * Polls every 300ms for up to 15 seconds.
   * Uses tracked runnables so polling is cancelled on resetState()/onDestroy().
   */
  private fun awaitReaderConnection(
    maxWaitMs: Long = READER_WAIT_TIMEOUT_MS,
    onConnected: (Reader) -> Unit,
    onTimeout: () -> Unit
  ) {
    val startTime = System.currentTimeMillis()
    val poller = object : Runnable {
      override fun run() {
        // Check if connected
        Terminal.getInstance().connectedReader?.let {
          onConnected(it)
          return
        }
        // No longer connecting and not connected → attempt failed
        if (!isConnectingReader.get()) {
          onTimeout()
          return
        }
        // Timeout check
        if (System.currentTimeMillis() - startTime > maxWaitMs) {
          // Force-reset stale connection state from hung operation (e.g., eagerPrepare)
          // This allows the caller to start a fresh discovery instead of cascading failures
          isConnectingReader.set(false)
          safeCancel(discoveryCancelable)
          discoveryCancelable = null
          onTimeout()
          return
        }
        // Continue polling (tracked for cleanup)
        postTrackedDelayed(this, READER_WAIT_POLL_MS)
      }
    }
    mainHandler.post(poller)
  }

  // ═══════════════════════════════════════════════════════════
  // NFC PREWARMUP
  // ═══════════════════════════════════════════════════════════

  /**
   * Warm up the NFC stack by running a brief discovery cycle.
   * Guards against overlapping discoveries — skips if any discovery is active.
   * Does NOT run automatically at startup (eagerPrepare handles that).
   */
  private fun prewarmupNfcInBackground() {
    if (!Terminal.isInitialized()) {
      Log.d(TAG, "Terminal not initialized, skipping NFC prewarmup")
      return
    }
    if (tokenProviderUrl == null) {
      Log.d(TAG, "Token provider URL not set, skipping NFC prewarmup")
      return
    }
    // Skip if any discovery or connection is already in progress
    if (isConnectingReader.get()) {
      Log.d(TAG, "Reader connecting, skipping NFC prewarmup")
      return
    }
    if (discoveryCancelable != null) {
      Log.d(TAG, "Discovery already active, skipping NFC prewarmup")
      return
    }

    val terminal = Terminal.getInstance()
    if (terminal.connectedReader != null) {
      Log.d(TAG, "Reader already connected, skipping NFC prewarmup")
      return
    }

    ensureLocationPermission(
      onGranted = {
        if (!isLocationServicesEnabled()) return@ensureLocationPermission
        // Double-check after permission grant (state may have changed)
        if (isConnectingReader.get() || discoveryCancelable != null) return@ensureLocationPermission

        Log.d(TAG, "Starting NFC prewarmup in background...")
        val config = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(false)

        val warmupCancelable = terminal.discoverReaders(config, object : DiscoveryListener {
          override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
            Log.d(TAG, "NFC prewarmup: Found ${readers.size} reader(s)")
          }
        }, object : Callback {
          override fun onSuccess() { Log.d(TAG, "NFC prewarmup discovery completed") }
          override fun onFailure(e: TerminalException) { Log.w(TAG, "NFC prewarmup discovery failed: ${e.message}") }
        })

        // Cancel after warmup window (tracked for cleanup)
        val cancelRunnable = Runnable {
          if (!warmupCancelable.isCompleted) {
            warmupCancelable.cancel(object : Callback {
              override fun onSuccess() { Log.d(TAG, "NFC prewarmup cancelled after warmup") }
              override fun onFailure(e: TerminalException) { Log.d(TAG, "NFC prewarmup cancel: ${e.message}") }
            })
          }
        }
        postTrackedDelayed(cancelRunnable, PREWARMUP_DURATION_MS)
      },
      onDenied = { Log.d(TAG, "Location permission denied, skipping NFC prewarmup") }
    )
  }

  /**
   * Prewarmup method callable from Flutter.
   */
  private fun prewarmupNfc(args: Map<*, *>, result: MethodChannel.Result) {
    Log.d(TAG, "Explicit prewarmup requested from Flutter")
    try {
      prewarmupNfcInBackground()
      result.success(mapOf("status" to "PREWARMUP_STARTED"))
    } catch (e: Exception) {
      Log.w(TAG, "Prewarmup failed (non-critical): ${e.message}")
      result.success(mapOf("status" to "PREWARMUP_SKIPPED", "reason" to (e.message ?: "Terminal not ready")))
    }
  }

  // ═══════════════════════════════════════════════════════════
  // DEVICE CAPABILITY CHECKS
  // ═══════════════════════════════════════════════════════════

  private fun checkDeviceCapability(isSimulated: Boolean = false): Pair<String, String>? {
    if (Build.VERSION.SDK_INT < TAP_TO_PAY_MIN_ANDROID_API) {
      return "UNSUPPORTED_OS" to "Tap to Pay requires Android 13 or later on Stripe Terminal SDK $STRIPE_SDK_VERSION"
    }

    getSecurityPatchError()?.let { return it }

    // Developer options must be disabled for Tap to Pay (skip in simulated mode for testing)
    if (!isSimulated) {
      val devOpts = try {
        Settings.Global.getInt(contentResolver, "development_settings_enabled", 0) != 0
      } catch (_: Exception) { false }
      if (devOpts) {
        return "DEVELOPER_OPTIONS_ENABLED" to "Developer Options, USB debugging, and Wi-Fi debugging must be disabled to use Tap to Pay"
      }
    }

    val nfc = NfcAdapter.getDefaultAdapter(this)
    if (nfc == null) return "NFC_UNSUPPORTED" to "No NFC hardware"
    if (!nfc.isEnabled) return "NFC_DISABLED" to "NFC disabled"
    val gms = GoogleApiAvailability.getInstance()
    val res = gms.isGooglePlayServicesAvailable(this)
    if (res != ConnectionResult.SUCCESS) return "TAP_TO_PAY_INSECURE_ENVIRONMENT" to "No Google Play Services"
    return null
  }

  private fun checkTerminalCapability(isSimulated: Boolean = false): Pair<String, String>? {
    if (!Terminal.isInitialized()) return null

    return try {
      val supportResult = Terminal.getInstance().supportsReadersOfType(
        DeviceType.TAP_TO_PAY_DEVICE,
        DiscoveryConfiguration.TapToPayDiscoveryConfiguration(isSimulated)
      )
      if (supportResult.isSupported) {
        null
      } else {
        "UNSUPPORTED_DEVICE" to (
          supportResult.error?.message
            ?: "This device does not meet the current Stripe Tap to Pay requirements (Android 13+, recent security patch, and hardware-backed keystore support)."
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Tap to Pay support check failed: ${e.message}", e)
      "UNSUPPORTED_DEVICE" to (e.message
        ?: "This device does not meet the current Stripe Tap to Pay requirements.")
    }
  }

  private fun getSecurityPatchDate(): LocalDate? {
    val patch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() } ?: return null
    return try {
      LocalDate.parse(patch)
    } catch (_: DateTimeParseException) {
      null
    }
  }

  private fun getSecurityPatchError(): Pair<String, String>? {
    val patchDate = getSecurityPatchDate() ?: return null
    val cutoffDate = LocalDate.now().minusMonths(MAX_SECURITY_PATCH_AGE_MONTHS)
    return if (patchDate.isBefore(cutoffDate)) {
      "OUTDATED_SECURITY_PATCH" to "Tap to Pay requires an Android security patch from the last 12 months"
    } else {
      null
    }
  }

  private fun hasRequiredHardwareKeystoreFeature(): Boolean? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      packageManager.hasSystemFeature(
        PackageManager.FEATURE_HARDWARE_KEYSTORE,
        REQUIRED_HARDWARE_KEYSTORE_VERSION
      )
    } else {
      null
    }
  }

  /**
   * Log device security capabilities (TEE/StrongBox). Runs once at startup.
   * Informational only — does NOT block payment flow.
   */
  private fun logDeviceSecurityCapabilities() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      Log.w(TAG, "Device security check: API < 23, hardware attestation not available")
      sendDebugLog("ℹ️ API < 23 — no hardware attestation")
      return
    }

    val isSunmiDevice = Build.MANUFACTURER.equals("SUNMI", ignoreCase = true)
    val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"

    try {
      val keyStore = KeyStore.getInstance("AndroidKeyStore")
      keyStore.load(null)
      val keyGenerator = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore"
      )

      // ── Step 1: Test basic TEE / AndroidKeyStore support ──
      var hasTee = false
      val teeAlias = "test_tee_key_${System.currentTimeMillis()}"
      try {
        val teeSpec = KeyGenParameterSpec.Builder(
          teeAlias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .build()
        keyGenerator.init(teeSpec)
        keyGenerator.generateKey()
        keyStore.deleteEntry(teeAlias)
        hasTee = true
        Log.i(TAG, "✅ TEE (Trusted Execution Environment) supported")
        sendDebugLog("✅ TEE hardware security supported")
      } catch (e: Exception) {
        Log.w(TAG, "⚠️ TEE key generation failed: ${e.javaClass.simpleName}: ${e.message}")
        sendDebugLog("⚠️ TEE not available — Tap to Pay compatibility will depend on Stripe's hardware checks")
      }

      // ── Step 2: Test StrongBox support (API 28+ only, optional) ──
      var hasStrongBox = false
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val sbAlias = "test_sb_key_${System.currentTimeMillis()}"
        try {
          val sbSpec = KeyGenParameterSpec.Builder(
            sbAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
          )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setIsStrongBoxBacked(true)
            .build()
          keyGenerator.init(sbSpec)
          keyGenerator.generateKey()
          keyStore.deleteEntry(sbAlias)
          hasStrongBox = true
          Log.i(TAG, "✅ StrongBox hardware security supported")
          sendDebugLog("✅ StrongBox hardware security supported")
        } catch (e: Exception) {
          // StrongBox NOT available — this is NORMAL on Sunmi Flex 3 and many other devices
          Log.i(TAG, "ℹ️ StrongBox not available (normal for $deviceLabel)")
          if (!isSunmiDevice) {
            sendDebugLog("ℹ️ StrongBox not available — hardware keystore support is still checked separately")
          }
        }
      }

      // ── Step 3: Summary (SDK 5.x requires Android 13, recent patches, and ECDH-capable hardware keystore) ──
      val secLevel = if (hasStrongBox) "StrongBox + TEE" else if (hasTee) "TEE" else "Software"
      val hwKeyStore = when (val supported = hasRequiredHardwareKeystoreFeature()) {
        true -> "v$REQUIRED_HARDWARE_KEYSTORE_VERSION+"
        false -> "missing/undetected"
        null -> "unknown"
      }
      val patchStatus = when {
        getSecurityPatchDate() == null -> "unknown"
        getSecurityPatchError() == null -> "recent"
        else -> "outdated"
      }
      if (isSunmiDevice) {
        Log.i(TAG, "══ SUNMI ${Build.MODEL}: Security=$secLevel | HWKeyStore=$hwKeyStore | Patch=$patchStatus ══")
        sendDebugLog("ℹ️ SUNMI ${Build.MODEL}: security=$secLevel | HWKeyStore=$hwKeyStore | Patch=$patchStatus | SDK $STRIPE_SDK_VERSION")
      } else {
        Log.i(TAG, "Device $deviceLabel: Security=$secLevel")
        sendDebugLog("ℹ️ $deviceLabel: security=$secLevel | HWKeyStore=$hwKeyStore | Patch=$patchStatus")
      }

    } catch (e: Exception) {
      Log.e(TAG, "Security capability check failed: ${e.message}")
      sendDebugLog("⚠️ Security check failed: ${e.message}")
    }
  }

  // ═══════════════════════════════════════════════════════════
  // PERMISSIONS
  // ═══════════════════════════════════════════════════════════

  private fun ensureLocationPermission(onGranted: () -> Unit, onDenied: () -> Unit) {
    if (locationPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
      onGranted()
    } else {
      pendingPermissionGranted = onGranted
      pendingPermissionDenied = onDenied
      ActivityCompat.requestPermissions(this, locationPermissions, locationPermissionRequestCode)
    }
  }

  private fun isLocationServicesEnabled(): Boolean {
    val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return try {
      lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
          lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) { false }
  }

  private fun requestMicrophonePermission(res: MethodChannel.Result) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      res.success(true)
    } else {
      pendingMicrophoneResult?.success(false)
      pendingMicrophoneResult = res
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), microphonePermissionRequestCode)
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    when (requestCode) {
      locationPermissionRequestCode -> {
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
          pendingPermissionGranted?.invoke()
        } else {
          pendingPermissionDenied?.invoke()
        }
        if (::printerManager.isInitialized) {
          printerManager.handleAppResume()
        }
        pendingPermissionGranted = null
        pendingPermissionDenied = null
      }
      microphonePermissionRequestCode -> {
        pendingMicrophoneResult?.success(grantResults.all { it == PackageManager.PERMISSION_GRANTED })
        pendingMicrophoneResult = null
        if (::printerManager.isInitialized) {
          printerManager.handleAppResume()
        }
      }
      bluetoothPermissionRequestCode -> {
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        pendingBluetoothPermissionResult?.success(mapOf("granted" to granted))
        pendingBluetoothPermissionResult = null
        if (::printerManager.isInitialized) {
          printerManager.handleAppResume()
        }
      }
      eagerPermissionRequestCode -> {
        val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        Log.d(TAG, "Eager permissions result: allGranted=$allGranted")
        if (::printerManager.isInitialized) {
          printerManager.handleAppResume()
        }
        if (allGranted) {
          eagerPrepareConfig?.let { (url, locId, isSim) ->
            if (Terminal.isInitialized()) {
              val terminal = Terminal.getInstance()
              if (terminal.connectedReader == null && !isConnectingReader.get()) {
                startEagerDiscoveryAndConnect(terminal, locId, isSim)
              }
            }
            eagerPrepareConfig = null
          }
        }
      }
      else -> {
        if (::printerManager.isInitialized) {
          printerManager.handleAppResume()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
      }
    }
  }

  // ═══════════════════════════════════════════════════════════
  // NFC & SETTINGS
  // ═══════════════════════════════════════════════════════════

  private fun getNfcStatus(res: MethodChannel.Result) {
    val s = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
    val e = NfcAdapter.getDefaultAdapter(this)?.isEnabled == true
    res.success(mapOf("supported" to s, "enabled" to e))
  }

  private fun getLocationStatus(res: MethodChannel.Result) {
    val hasPermission = locationPermissions.all {
      ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
    val enabled = isLocationServicesEnabled()
    res.success(mapOf("hasPermission" to hasPermission, "enabled" to enabled))
  }

  private fun getBluetoothStatus(res: MethodChannel.Result) {
    val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val supported = btManager?.adapter != null
    val enabled = btManager?.adapter?.isEnabled == true
    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
    res.success(mapOf(
      "supported" to supported,
      "enabled" to enabled,
      "hasPermission" to hasPermission
    ))
  }

  private fun requestBluetoothPermissions(res: MethodChannel.Result) {
    val needed = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        needed.add(Manifest.permission.BLUETOOTH_CONNECT)
      }
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
        needed.add(Manifest.permission.BLUETOOTH_SCAN)
      }
    } else {
      locationPermissions.forEach { perm ->
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
          needed.add(perm)
        }
      }
    }

    if (needed.isEmpty()) {
      res.success(mapOf("granted" to true))
      return
    }

    pendingBluetoothPermissionResult?.success(mapOf("granted" to false))
    pendingBluetoothPermissionResult = res
    ActivityCompat.requestPermissions(this, needed.toTypedArray(), bluetoothPermissionRequestCode)
  }

  private fun openNfcSettings() {
    startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  private fun openLocationSettings() {
    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  private fun openBluetoothSettings() {
    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  private fun openAppSettings() {
    val intent = Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.parse("package:$packageName")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
  }

  private fun openUsbSettings() {
    openAppSettings()
  }

  private fun getDeveloperOptionsStatus(res: MethodChannel.Result) {
    val enabled = try {
      Settings.Global.getInt(contentResolver, "development_settings_enabled", 0) != 0
    } catch (_: Exception) { false }
    res.success(mapOf("enabled" to enabled))
  }

  private fun openDeveloperSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  // ═══════════════════════════════════════════════════════════
  // TERMINAL LISTENER (SDK 5.x)
  // ═══════════════════════════════════════════════════════════

  override fun onConnectionStatusChange(status: ConnectionStatus) {
    Log.d(TAG, "Connection status: $status")
  }

  override fun onPaymentStatusChange(status: PaymentStatus) {
    Log.d(TAG, "Payment status: $status")
  }

  // ═══════════════════════════════════════════════════════════
  // TAP TO PAY READER LISTENER (SDK 5.x)
  // ═══════════════════════════════════════════════════════════

  override fun onDisconnect(reason: DisconnectReason) {
    Log.w(TAG, "Reader disconnected: $reason")
    sendDebugLog("⚠️ Reader disconnected: $reason")
    // Reset connection state so next payment attempt will reconnect
    isConnectingReader.set(false)
    // Notify Flutter side so it resets _readerConnected flag
    mainHandler.post {
      methodChannel?.invokeMethod("onReaderDisconnected", mapOf("reason" to reason.toString()))
    }
  }

  override fun onReaderReconnectStarted(reader: Reader, cancelReconnect: Cancelable, reason: DisconnectReason) {
    Log.w(TAG, "Reader reconnect started: $reason")
    sendDebugLog("🔄 Reader reconnecting...")
  }

  override fun onReaderReconnectSucceeded(reader: Reader) {
    Log.d(TAG, "Reader reconnected successfully")
    sendDebugLog("✅ Reader reconnected")
  }

  override fun onReaderReconnectFailed(reader: Reader) {
    Log.w(TAG, "Reader reconnect failed")
    sendDebugLog("❌ Reader reconnect failed")
  }

  // ═══════════════════════════════════════════════════════════
  // DEBUG & DEVICE INFO
  // ═══════════════════════════════════════════════════════════

  /**
   * Get device information for the debug screen.
   */
  private fun getDeviceInfo(result: MethodChannel.Result) {
    val isSunmi = Build.MANUFACTURER.equals("SUNMI", ignoreCase = true)
    val info = buildString {
      appendLine("Manufacturer: ${Build.MANUFACTURER}")
      appendLine("Brand: ${Build.BRAND}")
      appendLine("Model: ${Build.MODEL}")
      appendLine("Device: ${Build.DEVICE}")
      appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
      appendLine("Build: ${Build.FINGERPRINT}")

      // Check features
      val pm = packageManager
      appendLine("\nHardware Features:")
      if (pm.hasSystemFeature("android.hardware.nfc")) appendLine("✅ NFC") else appendLine("❌ NFC")
      if (pm.hasSystemFeature("android.hardware.nfc.hce")) appendLine("✅ NFC HCE") else appendLine("❌ NFC HCE")

      // StrongBox is optional — Sunmi Flex 3 works fine without it
      val hasStrongBox = pm.hasSystemFeature("android.hardware.strongbox_keystore")
      if (hasStrongBox) {
        appendLine("✅ StrongBox")
      } else {
        appendLine("ℹ️ StrongBox: Not available (not required)")
      }

      // TEE check — Sunmi Flex 3 supports TEE which is sufficient
      val hasTeeFeature = pm.hasSystemFeature("android.hardware.keystore")
      if (hasTeeFeature) {
        appendLine("✅ KeyStore (TEE)")
      } else {
        appendLine("ℹ️ KeyStore: Feature flag missing (may still work)")
      }

      // NFC adapter status
      val nfc = NfcAdapter.getDefaultAdapter(context)
      appendLine("\nNFC Status:")
      if (nfc != null) {
        appendLine("✅ NFC Adapter present")
        if (nfc.isEnabled) appendLine("✅ NFC Enabled") else appendLine("⚠️ NFC Disabled — enable in Settings")
      } else {
        appendLine("❌ No NFC Adapter")
      }

      // Google Play Services
      val gms = GoogleApiAvailability.getInstance()
      val gmsResult = gms.isGooglePlayServicesAvailable(context)
      if (gmsResult == ConnectionResult.SUCCESS) {
        appendLine("✅ Google Play Services")
      } else {
        appendLine("❌ Google Play Services: error $gmsResult")
      }

      // Location
      try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (gpsEnabled || networkEnabled) appendLine("✅ Location Services") else appendLine("⚠️ Location Services disabled")
      } catch (_: Exception) {
        appendLine("⚠️ Location Services: check failed")
      }

      // SDK & Environment Info
      appendLine("\nStripe Terminal SDK $STRIPE_SDK_VERSION:")
      appendLine("Security Patch: ${Build.VERSION.SECURITY_PATCH}")
      appendLine(
        if (getSecurityPatchError() == null) {
          "✅ Security Patch Age: within last 12 months"
        } else {
          "⚠️ Security Patch Age: older than 12 months"
        }
      )
      appendLine(
        when (val hardwareKeyStore = hasRequiredHardwareKeystoreFeature()) {
          true -> "✅ Hardware KeyStore: version $REQUIRED_HARDWARE_KEYSTORE_VERSION or newer"
          false -> "⚠️ Hardware KeyStore: version $REQUIRED_HARDWARE_KEYSTORE_VERSION not detected"
          null -> "ℹ️ Hardware KeyStore: version unavailable on this Android release"
        }
      )
      val devOpts = try {
        Settings.Global.getInt(contentResolver, "development_settings_enabled", 0) != 0
      } catch (_: Exception) { false }
      if (devOpts) {
        appendLine("⚠️ Developer Options: ENABLED (production Tap to Pay blocked on SDK $STRIPE_SDK_VERSION)")
      } else {
        appendLine("✅ Developer Options: Disabled")
      }

      // Terminal status
      if (Terminal.isInitialized()) {
        appendLine("✅ Terminal Initialized")
        val reader = Terminal.getInstance().connectedReader
        if (reader != null) {
          appendLine("✅ Reader: ${reader.label}")
        } else {
          appendLine("⏳ No reader connected (connects on first payment)")
        }

        // Definitive TTP support check
        try {
          val ttpResult = Terminal.getInstance().supportsReadersOfType(
            DeviceType.TAP_TO_PAY_DEVICE,
            DiscoveryConfiguration.TapToPayDiscoveryConfiguration(false)
          )
          appendLine("TTP Support: $ttpResult")
        } catch (e: Exception) {
          appendLine("TTP Support: Check failed (${e.message})")
        }
      } else {
        appendLine("❌ Terminal Not initialized")
      }

      // Sunmi-specific info
      if (isSunmi) {
        appendLine("\n🟢 SUNMI ${Build.MODEL}")
        appendLine("   Verify Android 13+, recent security patch, and hardware keystore support for SDK $STRIPE_SDK_VERSION")
      }
    }
    result.success(info)
  }

  // ═══════════════════════════════════════════════════════════
  // UTILITY
  // ═══════════════════════════════════════════════════════════

  private fun normalizeBaseUrl(u: String) = u.trimEnd('/')

  private fun fetchConnectionTokenFromBackend(baseUrl: String): String {
    var lastError: Exception? = null

    for (attempt in 0 until 2) {
      try {
        val startedAt = System.currentTimeMillis()
        val body = "{}".toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
          .url("$baseUrl/terminal/connection_token")
          .post(body)
          .build()

        return httpClient.newCall(req).execute().use { resp ->
          val rawBody = resp.body?.string().orEmpty()
          if (!resp.isSuccessful) {
            val detail = rawBody.take(120).ifBlank { "empty body" }
            throw IOException("Token endpoint HTTP ${resp.code}: $detail")
          }

          val secret = JSONObject(rawBody).optString("secret")
          if (secret.isBlank()) {
            throw IOException("Token endpoint returned no secret")
          }

          Log.d(TAG, "Connection token fetched in ${System.currentTimeMillis() - startedAt}ms")
          secret
        }
      } catch (e: Exception) {
        lastError = e
        val canRetry = attempt == 0 && e.isRetryableTokenFetchError()
        if (!canRetry) break
        Log.w(TAG, "Retrying connection token fetch after transient failure: ${e.message}")
        Thread.sleep(TOKEN_FETCH_RETRY_DELAY_MS)
      }
    }

    throw lastError ?: IOException("Token fetch failed")
  }

  private fun sendProgress(step: Int, message: String) {
    mainHandler.post {
      methodChannel?.invokeMethod("onTtpProgress", mapOf("step" to step, "message" to message))
    }
  }

  private fun sendDebugLog(message: String) {
    mainHandler.post {
      methodChannel?.invokeMethod("onDebugLog", mapOf("message" to message))
    }
  }

  /**
   * Stripe may return "disconnect first reader" during connection races,
   * even though a reader is already connected. Treat that as reusable success.
   */
  private fun shouldTreatAsAlreadyConnected(e: TerminalException): Boolean {
    val code = e.errorCode?.toString()?.uppercase() ?: ""
    val message = (e.errorMessage ?: e.message ?: "").uppercase()
    return code.contains("ALREADY_CONNECTED") ||
      message.contains("ALREADY CONNECTED") ||
      message.contains("DISCONNECT FIRST READER")
  }

  private fun Exception.isRetryableTokenFetchError(): Boolean {
    return this is SocketTimeoutException ||
      (this is IOException && this !is ConnectionTokenException)
  }
}
