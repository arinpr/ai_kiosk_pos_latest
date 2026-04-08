package com.example.ai_kiosk_pos.printer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

/**
 * USB Host API printer driver.
 *
 * Connects to USB thermal printers using Android's USB Host API.
 * Supports device discovery, permission requests, and bulk transfer printing.
 */
class UsbPrinterDriver(private val context: Context) {

  companion object {
    private const val TAG = "UsbPrinter"
    private const val ACTION_USB_PERMISSION = "com.example.ai_kiosk_pos.USB_PERMISSION"
    private const val TRANSFER_TIMEOUT_MS = 5000
    /** USB class for printers */
    private const val USB_CLASS_PRINTER = 7
  }

  private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
  private var connection: UsbDeviceConnection? = null
  private var usbInterface: UsbInterface? = null
  private var endpoint: UsbEndpoint? = null
  private var connectedDevice: UsbDevice? = null

  val isConnected: Boolean
    get() = connection != null && endpoint != null

  val connectedDeviceName: String?
    get() = connectedDevice?.productName ?: connectedDevice?.deviceName

  val connectedDeviceAddress: String?
    get() = connectedDevice?.deviceName

  // ═══════════════════════════════════════════════════════════
  // Discovery
  // ═══════════════════════════════════════════════════════════

  /**
   * Get list of connected USB devices that are printers.
   */
  fun getUsbPrinters(): List<PrinterInfo> {
    val devices = usbManager.deviceList.values

    return devices
      .filter { device -> isUsbPrinter(device) }
      .map { device ->
        PrinterInfo(
          name = device.productName ?: device.deviceName ?: "USB Printer",
          address = device.deviceName,
          type = "usb",
          isPrinter = true,
          isConnected = device.deviceName == connectedDeviceAddress && isConnected
        )
      }
  }

  /**
   * Check if a USB device is a printer.
   * Checks both device class and interface class.
   */
  private fun isUsbPrinter(device: UsbDevice): Boolean {
    // Check device class
    if (device.deviceClass == USB_CLASS_PRINTER) return true

    // Check interface classes
    for (i in 0 until device.interfaceCount) {
      val iface = device.getInterface(i)
      if (iface.interfaceClass == USB_CLASS_PRINTER) return true
      // Some printers present as vendor-specific (0xFF) with bulk endpoints
      if (iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) {
        for (j in 0 until iface.endpointCount) {
          val ep = iface.getEndpoint(j)
          if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
              ep.direction == UsbConstants.USB_DIR_OUT) {
            return true
          }
        }
      }
    }

    // Name heuristic
    val name = (device.productName ?: "").lowercase()
    return name.contains("printer") || name.contains("pos") || name.contains("thermal")
  }

  // ═══════════════════════════════════════════════════════════
  // Connection
  // ═══════════════════════════════════════════════════════════

  /**
   * Connect to a USB printer by device name (path).
   * Will request permission if not already granted.
   */
  suspend fun connect(deviceName: String): Boolean = withContext(Dispatchers.IO) {
    try {
      disconnect()

      val device = usbManager.deviceList[deviceName]
        ?: throw IOException("USB device not found: $deviceName")

      // Check/request permission
      if (!usbManager.hasPermission(device)) {
        val granted = requestPermission(device)
        if (!granted) {
          throw IOException("USB permission denied for $deviceName")
        }
      }

      // Find printer interface and OUT bulk endpoint
      var printerInterface: UsbInterface? = null
      var outEndpoint: UsbEndpoint? = null

      for (i in 0 until device.interfaceCount) {
        val iface = device.getInterface(i)
        for (j in 0 until iface.endpointCount) {
          val ep = iface.getEndpoint(j)
          if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
              ep.direction == UsbConstants.USB_DIR_OUT) {
            printerInterface = iface
            outEndpoint = ep
            break
          }
        }
        if (outEndpoint != null) break
      }

      if (printerInterface == null || outEndpoint == null) {
        throw IOException("No suitable bulk OUT endpoint found on $deviceName")
      }

      // Open connection
      val conn = usbManager.openDevice(device)
        ?: throw IOException("Failed to open USB device $deviceName")

      if (!conn.claimInterface(printerInterface, true)) {
        conn.close()
        throw IOException("Failed to claim USB interface on $deviceName")
      }

      connection = conn
      usbInterface = printerInterface
      endpoint = outEndpoint
      connectedDevice = device

      Log.i(TAG, "Connected to USB printer: ${device.productName} ($deviceName) ✅")
      return@withContext true

    } catch (e: Exception) {
      Log.e(TAG, "USB connection failed: ${e.message}", e)
      disconnect()
      return@withContext false
    }
  }

  /**
   * Request USB permission from the user.
   */
  private suspend fun requestPermission(device: UsbDevice): Boolean =
    suspendCancellableCoroutine { cont ->
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
          if (ACTION_USB_PERMISSION == intent.action) {
            context.unregisterReceiver(this)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (cont.isActive) cont.resume(granted)
          }
        }
      }

      val filter = IntentFilter(ACTION_USB_PERMISSION)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        context.registerReceiver(receiver, filter)
      }

      val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }

      val permIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
      usbManager.requestPermission(device, permIntent)

      cont.invokeOnCancellation {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
      }
    }

  /**
   * Disconnect from the USB printer.
   */
  fun disconnect() {
    try {
      usbInterface?.let { connection?.releaseInterface(it) }
    } catch (_: Exception) {}
    try {
      connection?.close()
    } catch (_: Exception) {}
    connection = null
    usbInterface = null
    endpoint = null
    connectedDevice = null
    Log.d(TAG, "USB disconnected")
  }

  // ═══════════════════════════════════════════════════════════
  // Printing
  // ═══════════════════════════════════════════════════════════

  /**
   * Send raw ESC/POS bytes to the connected USB printer via bulk transfer.
   */
  suspend fun print(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
    val conn = connection
    val ep = endpoint

    if (conn == null || ep == null || !isConnected) {
      Log.e(TAG, "No active USB printer connection")
      return@withContext false
    }

    try {
      // Send in chunks matching the endpoint's max packet size
      val maxPacket = ep.maxPacketSize
      val chunkSize = if (maxPacket > 0) maxPacket else 64
      var offset = 0

      while (offset < data.size) {
        val end = minOf(offset + chunkSize, data.size)
        val chunk = data.copyOfRange(offset, end)
        val transferred = conn.bulkTransfer(ep, chunk, chunk.size, TRANSFER_TIMEOUT_MS)

        if (transferred < 0) {
          Log.e(TAG, "USB bulk transfer failed at offset $offset")
          return@withContext false
        }

        offset = end
      }

      Log.i(TAG, "USB print data sent: ${data.size} bytes ✅")
      return@withContext true

    } catch (e: Exception) {
      Log.e(TAG, "USB print failed: ${e.message}", e)
      return@withContext false
    }
  }
}
