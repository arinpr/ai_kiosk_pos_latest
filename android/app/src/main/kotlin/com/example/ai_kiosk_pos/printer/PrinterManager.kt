package com.example.ai_kiosk_pos.printer

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified printer management layer.
 *
 * Abstracts over Bluetooth and USB printer drivers, providing a single
 * interface for the Flutter MethodChannel to interact with.
 *
 * Handles:
 * - Scanner aggregation (BT + USB)
 * - Connection with type routing (bluetooth vs usb)
 * - Receipt/KOT formatting + printing
 * - Printer state persistence (last-used printer)
 * - Auto-reconnect on startup
 * - Status reporting to Flutter
 */
class PrinterManager(private val context: Context) {

  companion object {
    private const val TAG = "PrinterManager"
    private const val PREFS_NAME = "megapos_printer_prefs"
    private const val KEY_LAST_PRINTER_ADDRESS = "last_printer_address"
    private const val KEY_LAST_PRINTER_TYPE = "last_printer_type"
    private const val KEY_LAST_PRINTER_NAME = "last_printer_name"
    private const val KEY_AUTO_PRINT_ENABLED = "auto_print_enabled"
    private const val KEY_PRINT_COPIES = "print_copies"
    private const val KEY_RESTAURANT_NAME = "restaurant_name"
    private const val KEY_RESTAURANT_ADDRESS = "restaurant_address"
    private const val KEY_RESTAURANT_PHONE = "restaurant_phone"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val btDriver = BluetoothPrinterDriver(context)
  private val usbDriver = UsbPrinterDriver(context)
  private val wifiDriver = WifiPrinterDriver()
  private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** Callback to send debug logs to Flutter */
  var debugLogSender: ((String) -> Unit)? = null

  /** Callback to send full printer status snapshots to Flutter/WebView */
  var statusSender: ((Map<String, Any>) -> Unit)? = null

  private var monitorStarted = false
  private var usbDetachReceiver: BroadcastReceiver? = null
  private var bluetoothReceiver: BroadcastReceiver? = null
  private var manualDisconnectRequested = false
  private var lastPrintFailureCode: String? = null
  private val printMutex = Mutex()
  @Volatile private var isPrinting = false

  init {
    registerUsbDetachReceiver()
    registerBluetoothReceiver()
    startStatusMonitoring()
  }

  // ═══════════════════════════════════════════════════════════
  // Initialization
  // ═══════════════════════════════════════════════════════════

  /**
   * Attempt to auto-reconnect to the last used printer on startup.
   * Called from MainActivity.configureFlutterEngine.
   */
  fun autoReconnectLastPrinter() {
    val address = prefs.getString(KEY_LAST_PRINTER_ADDRESS, null) ?: return
    val type = normalizePrinterType(prefs.getString(KEY_LAST_PRINTER_TYPE, "bluetooth") ?: "bluetooth")
    val name = prefs.getString(KEY_LAST_PRINTER_NAME, "Unknown") ?: "Unknown"

    sendLog("🖨️ Auto-reconnecting to $name ($address)...")

    scope.launch(Dispatchers.IO) {
      val success = when (type) {
        "bluetooth" -> btDriver.connect(address)
        "usb" -> usbDriver.connect(address)
        "wifi" -> wifiDriver.connect(address)
        "ethernet" -> wifiDriver.connect(address)
        else -> false
      }

      if (success) {
        sendLog("✅ Auto-reconnected to $name")
        emitStatus()
      } else {
        sendLog("⚠️ Auto-reconnect to $name failed (will retry on print)")
        disconnectDrivers()
        emitStatus()
      }
    }
  }

  /**
   * Called when the app returns from background.
   * Verifies existing handles and quietly tries one reconnect to the saved printer.
   */
  fun handleAppResume() {
    scope.launch(Dispatchers.IO) {
      val lost = disconnectIfConnectionLost("app resume")
      if (currentStatusMap()["connected"] != true) {
        reconnectLastPrinterQuietly()
      } else if (!lost) {
        emitStatus()
      }
    }
  }

  fun shutdown() {
    try { context.unregisterReceiver(usbDetachReceiver) } catch (_: Exception) {}
    try { context.unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
    usbDetachReceiver = null
    bluetoothReceiver = null
    scope.cancel()
  }

  // ═══════════════════════════════════════════════════════════
  // MethodChannel Handlers
  // ═══════════════════════════════════════════════════════════

  /**
   * Scan for available printers (Bluetooth paired + USB connected).
   */
  fun scanPrinters(result: MethodChannel.Result) {
    scope.launch {
      try {
        sendLog("🔍 Scanning for printers...")
        val printers = mutableListOf<Map<String, Any>>()
        val statusBefore = currentStatusMap()
        var btDevices = emptyList<PrinterInfo>()
        var bluetoothBlockCode: String? = null
        var bluetoothBlockMessage: String? = null

        when {
          !btDriver.isBluetoothAvailable -> {
            bluetoothBlockCode = "BLUETOOTH_OFF"
            bluetoothBlockMessage = "Bluetooth is not available on this device"
          }
          !btDriver.isBluetoothEnabled -> {
            bluetoothBlockCode = "BLUETOOTH_OFF"
            bluetoothBlockMessage = "Bluetooth is turned off"
          }
          !btDriver.hasPermission || !btDriver.hasScanPermission -> {
            bluetoothBlockCode = "BLUETOOTH_PERMISSION_DENIED"
            bluetoothBlockMessage = "Bluetooth permission is not granted"
          }
          !btDriver.hasLocationPermission -> {
            bluetoothBlockCode = "BLUETOOTH_PERMISSION_DENIED"
            bluetoothBlockMessage = "Location permission is required to scan Bluetooth printers"
          }
          else -> {
            // Bluetooth paired devices
            btDevices = btDriver.getPairedDevices()
            printers.addAll(btDevices.map { it.toMap() })
          }
        }

        // USB connected devices
        val usbDevices = usbDriver.getUsbPrinters()
        printers.addAll(usbDevices.map { it.toMap() })

        // Network printers reachable on the standard ESC/POS TCP port.
        val networkDevices = wifiDriver.scanNetworkPrinters()
        printers.addAll(networkDevices.map { it.toMap() })

        if (usbDriver.hasAnyUsbPrinter && !usbDriver.allPrinterPermissionsGranted) {
          val status = currentStatusMap()
          emitIfStatusChanged(statusBefore, status)
          result.success(errorResponse("USB_PERMISSION_DENIED", "USB printer permission is not granted", status))
          return@launch
        }

        if (printers.isEmpty()) {
          val status = currentStatusMap()
          emitIfStatusChanged(statusBefore, status)
          result.success(errorResponse(
            bluetoothBlockCode ?: "NO_PAIRED_PRINTERS",
            bluetoothBlockMessage ?: "No paired or connected printers found",
            status
          ))
          return@launch
        }

        sendLog("📋 Found ${printers.size} devices (${btDevices.size} BT, ${usbDevices.size} USB, ${networkDevices.size} network)")
        val status = currentStatusMap()
        emitIfStatusChanged(statusBefore, status)
        result.success(mapOf(
          "ok" to true,
          "printers" to printers,
          "bluetoothPermissionGranted" to (btDriver.hasPermission && btDriver.hasScanPermission),
          "bluetoothEnabled" to btDriver.isBluetoothEnabled,
          "bluetoothAvailable" to btDriver.isBluetoothAvailable,
          "status" to status
        ))
      } catch (e: Exception) {
        sendLog("❌ Scan failed: ${e.message}")
        result.success(errorResponse(errorCodeFor(e), e.message ?: "Scan failed"))
      }
    }
  }

  /**
   * Connect to a specific printer.
   */
  fun connectPrinter(args: Map<*, *>, result: MethodChannel.Result) {
    val address = args["address"] as? String
    val type = args["type"] as? String ?: "bluetooth"

    if (address.isNullOrBlank()) {
      result.success(errorResponse("PRINTER_DISCONNECTED", "Printer address is required"))
      return
    }

    scope.launch(Dispatchers.IO) {
      try {
        val normalizedType = normalizePrinterType(type)
        sendLog("🔗 Connecting to printer ($normalizedType: $address)...")

        if ((normalizedType == "wifi" || normalizedType == "ethernet") && !isValidNetworkPrinterAddress(address)) {
          val status = currentStatusMap()
          emitStatus(status)
          scope.launch(Dispatchers.Main) {
            result.success(errorResponse("NETWORK_UNREACHABLE", "Invalid network printer address", status))
          }
          return@launch
        }

        if (normalizedType == "bluetooth" && !btDriver.hasPermission) {
          val status = currentStatusMap()
          emitStatus(status)
          scope.launch(Dispatchers.Main) {
            result.success(errorResponse("BLUETOOTH_PERMISSION_DENIED", "Bluetooth permission not granted", status))
          }
          return@launch
        }
        if (normalizedType == "bluetooth" && !btDriver.isBluetoothEnabled) {
          val status = currentStatusMap()
          emitStatus(status)
          scope.launch(Dispatchers.Main) {
            result.success(errorResponse("BLUETOOTH_OFF", "Bluetooth is turned off", status))
          }
          return@launch
        }
        if (normalizedType == "bluetooth" && !btDriver.hasPairedDevice(address)) {
          val status = currentStatusMap()
          emitStatus(status)
          scope.launch(Dispatchers.Main) {
            result.success(errorResponse("PRINTER_NOT_PAIRED", "Printer is not paired with this device", status))
          }
          return@launch
        }
        val success = when (normalizedType) {
          "bluetooth" -> btDriver.connect(address)
          "usb" -> usbDriver.connect(address)
          "wifi" -> wifiDriver.connect(address)
          "ethernet" -> wifiDriver.connect(address)
          else -> {
            sendLog("❌ Unknown printer type: $normalizedType")
            false
          }
        }

        if (success) {
          disconnectDriversExcept(normalizedType)
          val connectedAddress = when (normalizedType) {
            "bluetooth" -> btDriver.connectedDeviceAddress ?: address
            "usb" -> usbDriver.connectedDeviceAddress ?: address
            "wifi", "ethernet" -> wifiDriver.connectedDeviceAddress ?: address
            else -> address
          }
          val name = when (normalizedType) {
            "bluetooth" -> btDriver.connectedDeviceName ?: "Bluetooth Printer"
            "usb" -> usbDriver.connectedDeviceName ?: "USB Printer"
            "wifi" -> wifiDriver.connectedDeviceName ?: "WiFi Printer"
            "ethernet" -> {
              val host = connectedAddress.substringBefore(":").ifBlank { connectedAddress }
              "Ethernet Printer ($host)"
            }
            else -> "Unknown"
          }

          // Save as last-used printer
          prefs.edit()
            .putString(KEY_LAST_PRINTER_ADDRESS, connectedAddress)
            .putString(KEY_LAST_PRINTER_TYPE, normalizedType)
            .putString(KEY_LAST_PRINTER_NAME, name)
            .apply()
          manualDisconnectRequested = false

          sendLog("✅ Connected to $name")
          val status = currentStatusMap()
          emitStatus(status)
          scope.launch(Dispatchers.Main) {
            result.success(mapOf(
              "ok" to true,
              "name" to name,
              "address" to connectedAddress,
              "type" to normalizedType,
              "status" to status
            ))
          }
        } else {
          sendLog("❌ Failed to connect to printer")
          val status = currentStatusMap()
          emitStatus(status)
          scope.launch(Dispatchers.Main) {
            val code = when (normalizedType) {
              "wifi", "ethernet" -> "NETWORK_UNREACHABLE"
              "usb" -> if (usbDriver.hasDevice(address) && !usbDriver.hasPermissionForDevice(address)) {
                "USB_PERMISSION_DENIED"
              } else {
                "PRINTER_DISCONNECTED"
              }
              else -> "PRINTER_DISCONNECTED"
            }
            result.success(errorResponse(code, "Failed to connect to printer at $address", status))
          }
        }
      } catch (e: Exception) {
        sendLog("❌ Connection error: ${e.message}")
        val status = currentStatusMap()
        emitStatus(status)
        scope.launch(Dispatchers.Main) {
          result.success(errorResponse(errorCodeFor(e), e.message ?: "Connection failed", status))
        }
      }
    }
  }

  /**
   * Disconnect from the current printer.
   */
  fun disconnectPrinter(result: MethodChannel.Result) {
    manualDisconnectRequested = true
    disconnectDrivers()
    sendLog("🔌 Printer disconnected")
    val status = currentStatusMap()
    emitStatus(status)
    result.success(mapOf("ok" to true, "status" to status))
  }

  /**
   * Get current printer connection status.
   */
  fun getPrinterStatus(result: MethodChannel.Result) {
    scope.launch(Dispatchers.IO) {
      disconnectIfConnectionLost("status check")
      val status = currentStatusMap()
      scope.launch(Dispatchers.Main) {
        result.success(status + mapOf("status" to status))
      }
    }
  }

  /**
   * Print a customer receipt.
   */
  fun printReceipt(args: Map<*, *>, result: MethodChannel.Result) {
    val orderData = extractOrderData(args)
    val copies = (args["copies"] as? Int) ?: prefs.getInt(KEY_PRINT_COPIES, 1)

    scope.launch(Dispatchers.IO) {
      try {
        // Add date/time if not provided
        if (!orderData.containsKey("dateTime") || (orderData["dateTime"] as? String).isNullOrBlank()) {
          (orderData as MutableMap)["dateTime"] = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.UK).format(Date())
        }

        // Add restaurant info from settings if not in payload
        enrichWithRestaurantInfo(orderData as MutableMap<String, Any?>)

        val receiptBytes = EscPosCommands.buildReceipt(orderData)

        var allSuccess = true
        repeat(copies) { copyIndex ->
          val success = printBytes(receiptBytes)
          if (!success) {
            allSuccess = false
            sendLog("❌ Receipt print failed (copy ${copyIndex + 1})")
          }
        }

        if (allSuccess) {
          sendLog("🖨️ Receipt printed (${copies} ${if (copies == 1) "copy" else "copies"}) ✅")
          scope.launch(Dispatchers.Main) {
            result.success(mapOf(
              "ok" to true,
              "copies" to copies,
              "status" to currentStatusMap().also { emitStatus(it) }
            ))
          }
        } else {
          val status = currentStatusMap()
          emitStatus(status)
          scope.launch(Dispatchers.Main) {
            result.success(errorResponse(currentPrintFailureCode(), "Failed to print receipt", status))
          }
        }
      } catch (e: Exception) {
        sendLog("❌ Receipt print error: ${e.message}")
        scope.launch(Dispatchers.Main) {
          val status = currentStatusMap()
          emitStatus(status)
          result.success(errorResponse(errorCodeFor(e), e.message ?: "Failed to print receipt", status))
        }
      }
    }
  }

  /**
   * Print a Kitchen Order Ticket.
   */
  fun printKot(args: Map<*, *>, result: MethodChannel.Result) {
    val orderData = extractOrderData(args)

    scope.launch(Dispatchers.IO) {
      try {
        if (!orderData.containsKey("dateTime") || (orderData["dateTime"] as? String).isNullOrBlank()) {
          (orderData as MutableMap)["dateTime"] = SimpleDateFormat("HH:mm", Locale.UK).format(Date())
        }

        val kotBytes = EscPosCommands.buildKot(orderData)
        val success = printBytes(kotBytes)

        if (success) {
          sendLog("🖨️ KOT printed ✅")
          scope.launch(Dispatchers.Main) {
            result.success(mapOf("ok" to true, "status" to currentStatusMap().also { emitStatus(it) }))
          }
        } else {
          val status = currentStatusMap()
          emitStatus(status)
          scope.launch(Dispatchers.Main) {
            result.success(errorResponse(currentPrintFailureCode(), "Failed to print KOT", status))
          }
        }
      } catch (e: Exception) {
        sendLog("❌ KOT print error: ${e.message}")
        scope.launch(Dispatchers.Main) {
          val status = currentStatusMap()
          emitStatus(status)
          result.success(errorResponse(errorCodeFor(e), e.message ?: "Failed to print KOT", status))
        }
      }
    }
  }

  /**
   * Print a report (future use — daily sales summary).
   */
  fun printReport(args: Map<*, *>, result: MethodChannel.Result) {
    // Placeholder for future report printing
    result.error("NOT_IMPLEMENTED", "Report printing coming soon", null)
  }

  /**
   * Print a test page to verify printer connectivity.
   */
  fun testPrint(result: MethodChannel.Result) {
    scope.launch(Dispatchers.IO) {
      try {
        sendLog("🖨️ Printing test page...")
        val testBytes = EscPosCommands.buildTestPage()
        val success = printBytes(testBytes)

        if (success) {
          sendLog("✅ Test page printed successfully")
          scope.launch(Dispatchers.Main) {
            result.success(mapOf("ok" to true, "status" to currentStatusMap().also { emitStatus(it) }))
          }
        } else {
          sendLog("❌ Test print failed — no printer connected")
          val status = currentStatusMap()
          emitStatus(status)
          scope.launch(Dispatchers.Main) {
            result.success(errorResponse(currentPrintFailureCode(), "No printer connected or print failed", status))
          }
        }
      } catch (e: Exception) {
        sendLog("❌ Test print error: ${e.message}")
        scope.launch(Dispatchers.Main) {
          val status = currentStatusMap()
          emitStatus(status)
          result.success(errorResponse(errorCodeFor(e), e.message ?: "Test print failed", status))
        }
      }
    }
  }

  /**
   * Update printer settings (auto-print, copies, restaurant info).
   */
  fun updateSettings(args: Map<*, *>, result: MethodChannel.Result) {
    val editor = prefs.edit()

    (args["autoPrintEnabled"] as? Boolean)?.let {
      editor.putBoolean(KEY_AUTO_PRINT_ENABLED, it)
    }
    (args["printCopies"] as? Int)?.let {
      editor.putInt(KEY_PRINT_COPIES, it.coerceIn(1, 5))
    }
    (args["restaurantName"] as? String)?.let {
      editor.putString(KEY_RESTAURANT_NAME, it)
    }
    (args["restaurantAddress"] as? String)?.let {
      editor.putString(KEY_RESTAURANT_ADDRESS, it)
    }
    (args["restaurantPhone"] as? String)?.let {
      editor.putString(KEY_RESTAURANT_PHONE, it)
    }

    editor.apply()
    sendLog("⚙️ Printer settings updated")
    val status = currentStatusMap()
    emitStatus(status)
    result.success(mapOf("ok" to true, "status" to status))
  }

  /**
   * Print raw ESC/POS bytes (base64-encoded from web).
   * The web app controls ALL formatting; this is just a dumb pipe.
   */
  fun printRaw(args: Map<*, *>, result: MethodChannel.Result) {
    val base64Data = args["data"] as? String
    val copies = (args["copies"] as? Int) ?: 1

    if (base64Data.isNullOrBlank()) {
      result.success(errorResponse("PRINTER_DISCONNECTED", "Missing 'data' (base64 ESC/POS bytes)"))
      return
    }

    scope.launch(Dispatchers.IO) {
      try {
        val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
        sendLog("\uD83D\uDDA8\uFE0F Printing raw data (${bytes.size} bytes, $copies copies)...")

        var allOk = true
        repeat(copies) { i ->
          if (i > 0) delay(600)
          if (!printBytes(bytes)) {
            allOk = false
            sendLog("❌ Raw print failed (copy ${i + 1})")
          }
        }

        scope.launch(Dispatchers.Main) {
          if (allOk) {
            sendLog("✅ Raw print complete ($copies copies)")
            result.success(mapOf(
              "ok" to true,
              "copies" to copies,
              "status" to currentStatusMap().also { emitStatus(it) }
            ))
          } else {
            val status = currentStatusMap()
            emitStatus(status)
            result.success(errorResponse(currentPrintFailureCode(), "Raw print failed", status))
          }
        }
      } catch (e: Exception) {
        sendLog("❌ Raw print error: ${e.message}")
        scope.launch(Dispatchers.Main) {
          val status = currentStatusMap()
          emitStatus(status)
          result.success(errorResponse(errorCodeFor(e), e.message ?: "Raw print failed", status))
        }
      }
    }
  }

  // ═══════════════════════════════════════════════════════════
  // Internal Helpers
  // ═══════════════════════════════════════════════════════════

  /**
   * Send bytes to whichever printer is currently connected.
   * Tries Bluetooth first, then USB (auto-reconnects if needed).
   */
  internal suspend fun printBytes(data: ByteArray): Boolean = printMutex.withLock {
    isPrinting = true
    try {
      printBytesLocked(data)
    } finally {
      isPrinting = false
    }
  }

  private suspend fun printBytesLocked(data: ByteArray): Boolean {
    lastPrintFailureCode = null

    if (btDriver.isConnected && !btDriver.isConnectionHealthy) {
      btDriver.disconnect()
    }
    if (usbDriver.isConnected && !usbDriver.isConnectionHealthy) {
      usbDriver.disconnect()
    }

    // Try Bluetooth first
    if (btDriver.isConnectionHealthy) {
      val ok = btDriver.print(data)
      if (!ok) {
        lastPrintFailureCode = "PRINTER_DISCONNECTED"
        reportPrintFailure("Bluetooth print failed")
      }
      return ok
    }

    // Try USB
    if (usbDriver.isConnectionHealthy) {
      val ok = usbDriver.print(data)
      if (!ok) {
        lastPrintFailureCode = "PRINTER_DISCONNECTED"
        reportPrintFailure("USB print failed")
      }
      return ok
    }

    // Try WiFi
    if (wifiDriver.isConnectionHealthy) {
      val ok = wifiDriver.print(data)
      if (!ok) {
        lastPrintFailureCode = "PRINTER_DISCONNECTED"
        reportPrintFailure("WiFi print failed")
      }
      return ok
    }

    // No active connection — try to reconnect to last known printer
    val lastAddress = prefs.getString(KEY_LAST_PRINTER_ADDRESS, null)
    val lastType = prefs.getString(KEY_LAST_PRINTER_TYPE, null)

    if (manualDisconnectRequested) {
      sendLog("ℹ️ Printer auto-reconnect skipped after manual disconnect")
      lastPrintFailureCode = "PRINTER_DISCONNECTED"
      emitStatus()
      return false
    }

    if (lastAddress != null && lastType != null) {
      sendLog("⟲ Auto-reconnecting to last printer...")
      val reconnected = when (lastType) {
        "bluetooth" -> btDriver.connect(lastAddress)
        "usb" -> usbDriver.connect(lastAddress)
        "wifi" -> wifiDriver.connect(lastAddress)
        "ethernet" -> wifiDriver.connect(lastAddress)
        else -> false
      }

      if (reconnected) {
        emitStatus()
        val ok = when (lastType) {
          "bluetooth" -> btDriver.print(data)
          "usb" -> usbDriver.print(data)
          "wifi" -> wifiDriver.print(data)
          "ethernet" -> wifiDriver.print(data)
          else -> false
        }
        if (!ok) {
          lastPrintFailureCode = "PRINTER_DISCONNECTED"
          reportPrintFailure("Print failed after reconnect")
        }
        return ok
      } else {
        sendLog("⚠️ Auto-reconnect failed")
        lastPrintFailureCode = when (normalizePrinterType(lastType)) {
          "wifi", "ethernet" -> "NETWORK_UNREACHABLE"
          "usb" -> if (usbDriver.allPrinterPermissionsGranted) "PRINTER_DISCONNECTED" else "USB_PERMISSION_DENIED"
          "bluetooth" -> if (btDriver.isBluetoothEnabled) "PRINTER_DISCONNECTED" else "BLUETOOTH_OFF"
          else -> "PRINTER_DISCONNECTED"
        }
        emitStatus()
      }
    }

    if (lastPrintFailureCode == null) {
      lastPrintFailureCode = "PRINTER_DISCONNECTED"
    }
    reportPrintFailure("No printer connected")
    return false
  }

  /**
   * Log print failure and disconnect drivers only when no transport is healthy.
   * Avoids tearing down BT after a successful write when health check is briefly stale.
   */
  private fun reportPrintFailure(reason: String) {
    sendLog("⚠️ Printer unavailable: $reason")
    val anyHealthy =
      btDriver.isConnectionHealthy ||
        usbDriver.isConnectionHealthy ||
        wifiDriver.isConnectionHealthy
    if (!anyHealthy) {
      disconnectDrivers()
    }
    emitStatus()
  }

  private fun currentStatusMap(): Map<String, Any> {
    val connectedStatus = when {
      btDriver.isConnectionHealthy -> mapOf(
        "connected" to true,
        "name" to (btDriver.connectedDeviceName ?: "Bluetooth Printer"),
        "address" to (btDriver.connectedDeviceAddress ?: ""),
        "type" to "bluetooth"
      )
      usbDriver.isConnectionHealthy -> mapOf(
        "connected" to true,
        "name" to (usbDriver.connectedDeviceName ?: "USB Printer"),
        "address" to (usbDriver.connectedDeviceAddress ?: ""),
        "type" to "usb"
      )
      wifiDriver.isConnectionHealthy -> mapOf(
        "connected" to true,
        "name" to networkStatusName(),
        "address" to (wifiDriver.connectedDeviceAddress ?: ""),
        "type" to networkStatusType()
      )
      else -> mapOf(
        "connected" to false,
        "name" to "",
        "address" to "",
        "type" to ""
      )
    }

    return connectedStatus + mapOf(
      "autoPrintEnabled" to prefs.getBoolean(KEY_AUTO_PRINT_ENABLED, true),
      "printCopies" to prefs.getInt(KEY_PRINT_COPIES, 1),
      "lastPrinterName" to (prefs.getString(KEY_LAST_PRINTER_NAME, "") ?: ""),
      "lastPrinterAddress" to (prefs.getString(KEY_LAST_PRINTER_ADDRESS, "") ?: ""),
      "lastPrinterType" to (prefs.getString(KEY_LAST_PRINTER_TYPE, "") ?: ""),
      "bluetoothEnabled" to btDriver.isBluetoothEnabled,
      "bluetoothPermissionGranted" to (btDriver.hasPermission && btDriver.hasScanPermission),
      "locationPermissionGranted" to hasLocationPermission(),
      "usbPermissionGranted" to usbDriver.allPrinterPermissionsGranted
    )
  }

  private fun emitStatus(status: Map<String, Any> = currentStatusMap()) {
    scope.launch(Dispatchers.Main) {
      statusSender?.invoke(status)
    }
  }

  private fun emitIfStatusChanged(before: Map<String, Any>, after: Map<String, Any>) {
    if (before != after) emitStatus(after)
  }

  private fun disconnectDrivers() {
    btDriver.disconnect()
    usbDriver.disconnect()
    wifiDriver.disconnect()
  }

  private fun disconnectDriversExcept(type: String) {
    when (normalizePrinterType(type)) {
      "bluetooth" -> {
        usbDriver.disconnect()
        wifiDriver.disconnect()
      }
      "usb" -> {
        btDriver.disconnect()
        wifiDriver.disconnect()
      }
      "wifi", "ethernet" -> {
        btDriver.disconnect()
        usbDriver.disconnect()
      }
      else -> disconnectDrivers()
    }
  }

  private suspend fun reconnectLastPrinterQuietly(): Boolean {
    val lastAddress = prefs.getString(KEY_LAST_PRINTER_ADDRESS, null)
    val lastType = prefs.getString(KEY_LAST_PRINTER_TYPE, null)
    if (lastAddress.isNullOrBlank() || lastType.isNullOrBlank()) {
      emitStatus()
      return false
    }
    if (manualDisconnectRequested) {
      emitStatus()
      return false
    }

    sendLog("⟲ Quiet reconnect to last printer...")
    val reconnected = when (lastType) {
      "bluetooth" -> btDriver.connect(lastAddress)
      "usb" -> usbDriver.connect(lastAddress)
      "wifi" -> wifiDriver.connect(lastAddress)
      "ethernet" -> wifiDriver.connect(lastAddress)
      else -> false
    }
    emitStatus()
    return reconnected
  }

  private fun normalizePrinterType(type: String): String {
    val value = type.trim().lowercase(Locale.ROOT)
    return when (value) {
      "" -> "bluetooth"
      "network", "lan", "tcp", "ethernet" -> "ethernet"
      "wifi", "wi-fi" -> "wifi"
      "usb" -> "usb"
      "bluetooth", "bt" -> "bluetooth"
      else -> value
    }
  }

  private fun networkStatusType(): String {
    return when (prefs.getString(KEY_LAST_PRINTER_TYPE, "") ?: "") {
      "ethernet" -> "ethernet"
      else -> "wifi"
    }
  }

  private fun networkStatusName(): String {
    val address = wifiDriver.connectedDeviceAddress ?: ""
    val host = address.substringBefore(":").ifBlank { address }
    return when (networkStatusType()) {
      "ethernet" -> "Ethernet Printer ($host)"
      else -> wifiDriver.connectedDeviceName ?: "WiFi Printer"
    }
  }

  private fun errorResponse(
    code: String,
    message: String,
    status: Map<String, Any> = currentStatusMap()
  ): Map<String, Any> = mapOf(
    "ok" to false,
    "message" to message,
    "error" to message,
    "errorCode" to code,
    "code" to code,
    "status" to status
  )

  private fun errorCodeFor(e: Exception): String {
    val message = (e.message ?: "").uppercase(Locale.ROOT)
    return when {
      "USB" in message && ("PERMISSION" in message || "DENIED" in message) -> "USB_PERMISSION_DENIED"
      "BLUETOOTH" in message && ("PERMISSION" in message || "DENIED" in message) -> "BLUETOOTH_PERMISSION_DENIED"
      "LOCATION" in message && ("PERMISSION" in message || "DENIED" in message) -> "BLUETOOTH_PERMISSION_DENIED"
      "PERMISSION" in message || "DENIED" in message -> "BLUETOOTH_PERMISSION_DENIED"
      "NETWORK" in message || "HOST" in message || "TIMED" in message || "UNREACHABLE" in message -> "NETWORK_UNREACHABLE"
      "OFFLINE" in message || "NOT ENABLED" in message || "NOT AVAILABLE" in message -> "PRINTER_DISCONNECTED"
      "INVALID" in message || "ADDRESS" in message || "HOST" in message -> "NETWORK_UNREACHABLE"
      else -> currentPrintFailureCode()
    }
  }

  private fun currentPrintFailureCode(): String {
    lastPrintFailureCode?.let { return it }

    val activeType = when {
      btDriver.isConnectionHealthy -> "bluetooth"
      usbDriver.isConnectionHealthy -> "usb"
      wifiDriver.isConnectionHealthy -> networkStatusType()
      else -> prefs.getString(KEY_LAST_PRINTER_TYPE, "") ?: ""
    }
    return when (normalizePrinterType(activeType)) {
      "usb" -> if (usbDriver.allPrinterPermissionsGranted) "PRINTER_DISCONNECTED" else "USB_PERMISSION_DENIED"
      "wifi", "ethernet" -> "NETWORK_UNREACHABLE"
      else -> "PRINTER_DISCONNECTED"
    }
  }

  private fun hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED
  }

  private fun isValidNetworkPrinterAddress(address: String): Boolean {
    val parts = address.trim().split(":")
    val host = parts.firstOrNull()?.trim().orEmpty()
    if (host.isBlank()) return false
    if (parts.size > 2) return false
    if (parts.size == 2) {
      val port = parts[1].toIntOrNull() ?: return false
      if (port !in 1..65535) return false
    }
    val ipv4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    if (ipv4.matches(host)) {
      return host.split(".").all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
    }
    return Regex("""^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$""").matches(host)
  }

  private suspend fun disconnectIfConnectionLost(reason: String): Boolean {
    if (isPrinting) return false

    val wifiLost = wifiDriver.isConnected && !wifiDriver.verifyConnection()
    val lost = (btDriver.isConnected && !btDriver.isConnectionHealthy) ||
      (usbDriver.isConnected && !usbDriver.isConnectionHealthy) ||
      wifiLost

    if (lost) {
      sendLog("⚠️ Printer connection lost ($reason)")
      disconnectDrivers()
      emitStatus()
    }

    return lost
  }

  private fun startStatusMonitoring() {
    if (monitorStarted) return
    monitorStarted = true
    scope.launch(Dispatchers.IO) {
      // Bug #12: Check isActive so the loop exits cleanly on scope.cancel()
      while (isActive) {
        delay(5_000)
        if (isActive) disconnectIfConnectionLost("monitor")
      }
    }
  }

  private fun registerUsbDetachReceiver() {
    if (usbDetachReceiver != null) return

    usbDetachReceiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
        if (usbDriver.isConnected) {
          sendLog("⚠️ USB printer detached")
          usbDriver.disconnect()
          emitStatus()
        }
      }
    }

    val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(usbDetachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      context.registerReceiver(usbDetachReceiver, filter)
    }
  }

  private fun registerBluetoothReceiver() {
    if (bluetoothReceiver != null) return

    bluetoothReceiver = object : BroadcastReceiver() {
      @SuppressLint("MissingPermission")
      override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
          BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
              @Suppress("DEPRECATION")
              intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
            }
            val address = device?.address
            if (btDriver.isConnected && (address == null || address == btDriver.connectedDeviceAddress)) {
              sendLog("⚠️ Bluetooth printer disconnected")
              btDriver.disconnect()
              emitStatus()
            }
          }
          BluetoothAdapter.ACTION_STATE_CHANGED -> {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
              if (btDriver.isConnected) {
                sendLog("⚠️ Bluetooth turned off")
                btDriver.disconnect()
              }
              emitStatus()
            } else if (state == BluetoothAdapter.STATE_ON) {
              emitStatus()
            }
          }
        }
      }
    }

    val filter = IntentFilter().apply {
      addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
      addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      context.registerReceiver(bluetoothReceiver, filter)
    }
  }

  /**
   * Extract order data from MethodChannel arguments into a mutable Map.
   */
  @Suppress("UNCHECKED_CAST")
  private fun extractOrderData(args: Map<*, *>): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()

    // Copy all string/number fields
    for ((key, value) in args) {
      when (key) {
        is String -> result[key] = value
      }
    }

    return result
  }

  /**
   * Add restaurant info from SharedPreferences to order data
   * if not already present in the payload.
   */
  private fun enrichWithRestaurantInfo(data: MutableMap<String, Any?>) {
    if (data["restaurantName"] == null || (data["restaurantName"] as? String).isNullOrBlank()) {
      data["restaurantName"] = prefs.getString(KEY_RESTAURANT_NAME, "MEGAPOS")
    }
    if (data["restaurantAddress"] == null || (data["restaurantAddress"] as? String).isNullOrBlank()) {
      data["restaurantAddress"] = prefs.getString(KEY_RESTAURANT_ADDRESS, null)
    }
    if (data["restaurantPhone"] == null || (data["restaurantPhone"] as? String).isNullOrBlank()) {
      data["restaurantPhone"] = prefs.getString(KEY_RESTAURANT_PHONE, null)
    }
  }

  private fun sendLog(message: String) {
    Log.d(TAG, message)
    debugLogSender?.invoke(message)
  }
}
