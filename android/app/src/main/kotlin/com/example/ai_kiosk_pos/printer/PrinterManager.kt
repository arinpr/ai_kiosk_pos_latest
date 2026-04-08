package com.example.ai_kiosk_pos.printer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
  private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** Callback to send debug logs to Flutter */
  var debugLogSender: ((String) -> Unit)? = null

  // ═══════════════════════════════════════════════════════════
  // Initialization
  // ═══════════════════════════════════════════════════════════

  /**
   * Attempt to auto-reconnect to the last used printer on startup.
   * Called from MainActivity.configureFlutterEngine.
   */
  fun autoReconnectLastPrinter() {
    val address = prefs.getString(KEY_LAST_PRINTER_ADDRESS, null) ?: return
    val type = prefs.getString(KEY_LAST_PRINTER_TYPE, "bluetooth") ?: "bluetooth"
    val name = prefs.getString(KEY_LAST_PRINTER_NAME, "Unknown") ?: "Unknown"

    sendLog("🖨️ Auto-reconnecting to $name ($address)...")

    scope.launch(Dispatchers.IO) {
      val success = when (type) {
        "bluetooth" -> btDriver.connect(address)
        "usb" -> usbDriver.connect(address)
        else -> false
      }

      if (success) {
        sendLog("✅ Auto-reconnected to $name")
      } else {
        sendLog("⚠️ Auto-reconnect to $name failed (will retry on print)")
      }
    }
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

        // Bluetooth paired devices
        val btDevices = btDriver.getPairedDevices()
        printers.addAll(btDevices.map { it.toMap() })

        // USB connected devices
        val usbDevices = usbDriver.getUsbPrinters()
        printers.addAll(usbDevices.map { it.toMap() })

        sendLog("📋 Found ${printers.size} devices (${btDevices.size} BT, ${usbDevices.size} USB)")
        result.success(mapOf(
          "ok" to true,
          "printers" to printers
        ))
      } catch (e: Exception) {
        sendLog("❌ Scan failed: ${e.message}")
        result.error("SCAN_FAILED", e.message, null)
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
      result.error("INVALID_ARGS", "Printer address is required", null)
      return
    }

    scope.launch(Dispatchers.IO) {
      try {
        sendLog("🔗 Connecting to printer ($type: $address)...")

        val success = when (type) {
          "bluetooth" -> btDriver.connect(address)
          "usb" -> usbDriver.connect(address)
          else -> {
            sendLog("❌ Unknown printer type: $type")
            false
          }
        }

        if (success) {
          val name = when (type) {
            "bluetooth" -> btDriver.connectedDeviceName ?: "Bluetooth Printer"
            "usb" -> usbDriver.connectedDeviceName ?: "USB Printer"
            else -> "Unknown"
          }

          // Save as last-used printer
          prefs.edit()
            .putString(KEY_LAST_PRINTER_ADDRESS, address)
            .putString(KEY_LAST_PRINTER_TYPE, type)
            .putString(KEY_LAST_PRINTER_NAME, name)
            .apply()

          sendLog("✅ Connected to $name")
          scope.launch(Dispatchers.Main) {
            result.success(mapOf(
              "ok" to true,
              "name" to name,
              "address" to address,
              "type" to type
            ))
          }
        } else {
          sendLog("❌ Failed to connect to printer")
          scope.launch(Dispatchers.Main) {
            result.error("CONNECT_FAILED", "Failed to connect to printer at $address", null)
          }
        }
      } catch (e: Exception) {
        sendLog("❌ Connection error: ${e.message}")
        scope.launch(Dispatchers.Main) {
          result.error("CONNECT_FAILED", e.message, null)
        }
      }
    }
  }

  /**
   * Disconnect from the current printer.
   */
  fun disconnectPrinter(result: MethodChannel.Result) {
    btDriver.disconnect()
    usbDriver.disconnect()
    sendLog("🔌 Printer disconnected")
    result.success(mapOf("ok" to true))
  }

  /**
   * Get current printer connection status.
   */
  fun getPrinterStatus(result: MethodChannel.Result) {
    val btConnected = btDriver.isConnected
    val usbConnected = usbDriver.isConnected

    val status = when {
      btConnected -> mapOf(
        "connected" to true,
        "name" to (btDriver.connectedDeviceName ?: "Bluetooth Printer"),
        "address" to (btDriver.connectedDeviceAddress ?: ""),
        "type" to "bluetooth"
      )
      usbConnected -> mapOf(
        "connected" to true,
        "name" to (usbDriver.connectedDeviceName ?: "USB Printer"),
        "address" to (usbDriver.connectedDeviceAddress ?: ""),
        "type" to "usb"
      )
      else -> mapOf(
        "connected" to false,
        "name" to "",
        "address" to "",
        "type" to ""
      )
    }

    val autoPrint = prefs.getBoolean(KEY_AUTO_PRINT_ENABLED, true)
    val copies = prefs.getInt(KEY_PRINT_COPIES, 1)

    result.success(status + mapOf(
      "autoPrintEnabled" to autoPrint,
      "printCopies" to copies,
      "lastPrinterName" to (prefs.getString(KEY_LAST_PRINTER_NAME, "") ?: ""),
      "lastPrinterAddress" to (prefs.getString(KEY_LAST_PRINTER_ADDRESS, "") ?: ""),
      "lastPrinterType" to (prefs.getString(KEY_LAST_PRINTER_TYPE, "") ?: "")
    ))
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
            result.success(mapOf("ok" to true, "copies" to copies))
          }
        } else {
          scope.launch(Dispatchers.Main) {
            result.error("PRINT_FAILED", "Failed to print receipt", null)
          }
        }
      } catch (e: Exception) {
        sendLog("❌ Receipt print error: ${e.message}")
        scope.launch(Dispatchers.Main) {
          result.error("PRINT_FAILED", e.message, null)
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
            result.success(mapOf("ok" to true))
          }
        } else {
          scope.launch(Dispatchers.Main) {
            result.error("PRINT_FAILED", "Failed to print KOT", null)
          }
        }
      } catch (e: Exception) {
        sendLog("❌ KOT print error: ${e.message}")
        scope.launch(Dispatchers.Main) {
          result.error("PRINT_FAILED", e.message, null)
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
            result.success(mapOf("ok" to true))
          }
        } else {
          sendLog("❌ Test print failed — no printer connected")
          scope.launch(Dispatchers.Main) {
            result.error("PRINT_FAILED", "No printer connected or print failed", null)
          }
        }
      } catch (e: Exception) {
        sendLog("❌ Test print error: ${e.message}")
        scope.launch(Dispatchers.Main) {
          result.error("PRINT_FAILED", e.message, null)
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
    result.success(mapOf("ok" to true))
  }

  // ═══════════════════════════════════════════════════════════
  // Internal Helpers
  // ═══════════════════════════════════════════════════════════

  /**
   * Send bytes to whichever printer is currently connected.
   * Tries Bluetooth first, then USB (auto-reconnects if needed).
   */
  private suspend fun printBytes(data: ByteArray): Boolean {
    // Try Bluetooth first
    if (btDriver.isConnected) {
      return btDriver.print(data)
    }

    // Try USB
    if (usbDriver.isConnected) {
      return usbDriver.print(data)
    }

    // No active connection — try to reconnect to last known printer
    val lastAddress = prefs.getString(KEY_LAST_PRINTER_ADDRESS, null)
    val lastType = prefs.getString(KEY_LAST_PRINTER_TYPE, null)

    if (lastAddress != null && lastType != null) {
      sendLog("⟲ Auto-reconnecting to last printer...")
      val reconnected = when (lastType) {
        "bluetooth" -> btDriver.connect(lastAddress)
        "usb" -> usbDriver.connect(lastAddress)
        else -> false
      }

      if (reconnected) {
        return when (lastType) {
          "bluetooth" -> btDriver.print(data)
          "usb" -> usbDriver.print(data)
          else -> false
        }
      }
    }

    return false
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
