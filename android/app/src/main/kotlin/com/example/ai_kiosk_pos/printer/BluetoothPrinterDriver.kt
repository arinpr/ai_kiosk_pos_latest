package com.example.ai_kiosk_pos.printer

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth SPP (Serial Port Profile) printer driver.
 *
 * Connects to Bluetooth thermal printers using the standard SPP UUID.
 * Supports scanning paired devices, connecting, sending ESC/POS data,
 * and auto-reconnect.
 */
class BluetoothPrinterDriver(private val context: Context) {

  companion object {
    private const val TAG = "BtPrinter"
    /** Standard SPP UUID for serial Bluetooth communication */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    /** Common Bluetooth device classes for printers */
    private val PRINTER_DEVICE_CLASSES = setOf(
      0x0680, // Imaging: Printer
      0x0600, // Imaging: Uncategorized
    )
  }

  private var socket: BluetoothSocket? = null
  private var outputStream: OutputStream? = null
  private var connectedDevice: BluetoothDevice? = null

  val isConnected: Boolean
    get() = socket != null && outputStream != null

  /** socket.isConnected is unreliable on many SPP thermal printers — use open streams instead. */
  val isConnectionHealthy: Boolean
    get() = isBluetoothEnabled && socket != null && outputStream != null

  val connectedDeviceName: String?
    get() = connectedDevice?.name

  val connectedDeviceAddress: String?
    get() = connectedDevice?.address

  val hasPermission: Boolean
    get() = hasBluetoothPermission()

  val hasScanPermission: Boolean
    get() = hasBluetoothScanPermission()

  val hasLocationPermission: Boolean
    get() = hasBluetoothLocationPermission()

  val isBluetoothEnabled: Boolean
    get() {
      val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
      return btManager?.adapter?.isEnabled == true
    }

  val isBluetoothAvailable: Boolean
    get() {
      val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
      return btManager?.adapter != null
    }

  // ═══════════════════════════════════════════════════════════
  // Scanning
  // ═══════════════════════════════════════════════════════════

  /**
   * Get list of paired Bluetooth devices that are likely printers.
   * Filters out phones/headsets and other non-printer paired devices.
   */
  @SuppressLint("MissingPermission")
  fun getPairedDevices(): List<PrinterInfo> {
    if (!hasBluetoothPermission()) {
      Log.w(TAG, "Bluetooth permission not granted")
      return emptyList()
    }

    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = btManager?.adapter

    if (adapter == null || !adapter.isEnabled) {
      Log.w(TAG, "Bluetooth not available or not enabled")
      return emptyList()
    }

    return adapter.bondedDevices
      .filter { device ->
        // Filter out BLE-only devices (type 2 = BLE, type 3 = dual, type 1 = classic)
        device.type != BluetoothDevice.DEVICE_TYPE_LE && !device.name.isNullOrBlank()
      }
      .filter { device -> isPrinterDevice(device) }
      .map { device ->
        PrinterInfo(
          name = device.name ?: "Unknown",
          address = device.address,
          type = "bluetooth",
          isPrinter = true,
          isConnected = device.address == connectedDeviceAddress && isConnected
        )
      }
      .sortedByDescending { it.isConnected } // connected printer always first
  }

  @SuppressLint("MissingPermission")
  fun hasPairedDevice(address: String): Boolean {
    if (!hasBluetoothPermission()) return false
    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = btManager?.adapter ?: return false
    if (!adapter.isEnabled) return false
    return adapter.bondedDevices.any { it.address.equals(address, ignoreCase = true) }
  }

  /**
   * Heuristic check if a Bluetooth device is likely a printer.
   * Checks device class and common printer name patterns.
   */
  @SuppressLint("MissingPermission")
  private fun isPrinterDevice(device: BluetoothDevice): Boolean {
    val name = (device.name ?: "").lowercase()
    val nonPrinterKeywords = listOf(
      "phone", "iphone", "android", "samsung", "galaxy", "oppo", "oneplus",
      "redmi", "xiaomi", "vivo", "realme", "pixel", "watch", "band",
      "headset", "headphone", "earbuds", "airpods", "buds", "speaker",
      "audio", "keyboard", "mouse", "laptop", "macbook", "desktop", "tv", "car"
    )
    if (nonPrinterKeywords.any { name.contains(it) }) return false

    // Check device class
    val bluetoothClass = device.bluetoothClass
    val deviceClass = bluetoothClass?.deviceClass ?: 0
    if (deviceClass in PRINTER_DEVICE_CLASSES) return true
    if (bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING) return true

    // Check name patterns common for thermal printers
    val printerKeywords = listOf(
      "printer", "print", "pos", "thermal", "receipt", "escpos",
      "star ", "epson", "bixolon", "xprinter", "munbyn", "goojprt",
      "mtp-", "spp-", "bt-", "gprinter", "rongta", "hprt", "sewoo",
      "zjiang", "milestone", "iposprinter", "hiloti", "nyear",
      "sunmi", "imin", "sprt", "pt-", "rp-", "xp-", "kpc", "kpc307", "uewb"
    )
    return printerKeywords.any { name.contains(it) }
  }

  // ═══════════════════════════════════════════════════════════
  // Connection
  // ═══════════════════════════════════════════════════════════

  /**
   * Connect to a Bluetooth printer by MAC address.
   * Must be called from a coroutine (IO dispatcher).
   */
  @SuppressLint("MissingPermission")
  suspend fun connect(address: String): Boolean = withContext(Dispatchers.IO) {
    try {
      if (!hasBluetoothPermission()) {
        throw IOException("Bluetooth permission not granted")
      }

      // Disconnect existing connection first
      disconnect()

      val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
      val adapter = btManager?.adapter
        ?: throw IOException("Bluetooth adapter not available")

      if (!adapter.isEnabled) {
        throw IOException("Bluetooth is not enabled")
      }

      // Cancel discovery if running (interferes with connection)
      adapter.cancelDiscovery()

      val device = adapter.getRemoteDevice(address)
        ?: throw IOException("Device not found: $address")

      Log.i(TAG, "Connecting to ${device.name} ($address)...")

      // Try to create socket. Use reflection fallback for some Chinese printers
      // that don't properly support createRfcommSocketToServiceRecord.
      val btSocket = try {
        device.createRfcommSocketToServiceRecord(SPP_UUID)
      } catch (e: IOException) {
        Log.w(TAG, "Standard RFCOMM failed, trying fallback method...")
        // Fallback: use hidden createRfcommSocket method
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        method.invoke(device, 1) as BluetoothSocket
      }

      // Connect with timeout (handled by the socket itself, ~12s default)
      btSocket.connect()

      socket = btSocket
      outputStream = btSocket.outputStream
      connectedDevice = device

      Log.i(TAG, "Connected to ${device.name} ($address) ✅")
      return@withContext true

    } catch (e: Exception) {
      Log.e(TAG, "Connection failed: ${e.message}", e)
      disconnect()
      return@withContext false
    }
  }

  /**
   * Disconnect from the current printer.
   */
  fun disconnect() {
    try {
      outputStream?.close()
    } catch (_: Exception) {}
    try {
      socket?.close()
    } catch (_: Exception) {}
    outputStream = null
    socket = null
    connectedDevice = null
    Log.d(TAG, "Disconnected")
  }

  // ═══════════════════════════════════════════════════════════
  // Printing
  // ═══════════════════════════════════════════════════════════

  /**
   * Send raw ESC/POS bytes to the connected printer.
   * Automatically attempts reconnect if the connection was lost.
   */
  suspend fun print(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
    val address = connectedDeviceAddress

    if (!isConnectionHealthy && address != null) {
      Log.w(TAG, "Connection unhealthy, attempting reconnect to $address...")
      if (!connect(address)) {
        Log.e(TAG, "Reconnect failed — clearing stale connection state")
        disconnect()
        return@withContext false
      }
    }

    val stream = outputStream
    if (stream == null || !isConnectionHealthy) {
      Log.e(TAG, "No healthy printer connection")
      return@withContext false
    }

    try {
      writeChunks(stream, data)
      Log.i(TAG, "Print data sent: ${data.size} bytes ✅")
      return@withContext true
    } catch (e: IOException) {
      Log.e(TAG, "Print failed: ${e.message}", e)
      disconnect()

      if (address != null) {
        Log.w(TAG, "Retrying print after reconnect to $address...")
        if (connect(address)) {
          val retryStream = outputStream
          if (retryStream != null && isConnectionHealthy) {
            try {
              writeChunks(retryStream, data)
              Log.i(TAG, "Print retry succeeded: ${data.size} bytes ✅")
              return@withContext true
            } catch (retryError: IOException) {
              Log.e(TAG, "Print retry failed: ${retryError.message}", retryError)
              disconnect()
            }
          }
        }
      }

      return@withContext false
    }
  }

  private fun writeChunks(stream: OutputStream, data: ByteArray) {
    val chunkSize = when {
      data.size > 32_768 -> 128
      data.size > 8_192 -> 192
      else -> 256
    }
    val interChunkMs = when {
      data.size > 32_768 -> 100L
      data.size > 8_192 -> 80L
      else -> 60L
    }
    val postPrintMs = when {
      data.size > 32_768 -> 800L
      data.size > 8_192 -> 600L
      data.size > 512 -> 500L
      else -> 350L
    }

    var offset = 0
    while (offset < data.size) {
      val end = minOf(offset + chunkSize, data.size)
      stream.write(data, offset, end - offset)
      stream.flush()
      offset = end

      if (offset < data.size) {
        Thread.sleep(interChunkMs)
      }
    }

    Thread.sleep(postPrintMs)
  }

  private fun hasBluetoothPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_CONNECT
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  private fun hasBluetoothScanPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_SCAN
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  private fun hasBluetoothLocationPermission(): Boolean {
    return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }
}

/**
 * Info about a discovered printer.
 */
data class PrinterInfo(
  val name: String,
  val address: String,
  val type: String, // "bluetooth" or "usb"
  val isPrinter: Boolean = false,
  val isConnected: Boolean = false
) {
  fun toMap(): Map<String, Any> = mapOf(
    "name" to name,
    "address" to address,
    "type" to type,
    "isPrinter" to isPrinter,
    "isConnected" to isConnected
  )
}
