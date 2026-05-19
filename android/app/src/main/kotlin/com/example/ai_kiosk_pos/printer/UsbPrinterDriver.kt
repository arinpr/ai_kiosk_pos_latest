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
import kotlinx.coroutines.withTimeoutOrNull
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
    private const val PERMISSION_TIMEOUT_MS = 30_000L // 30 seconds for user to grant permission
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

  val isConnectionHealthy: Boolean
    get() {
      val address = connectedDevice?.deviceName
      return connection != null &&
        endpoint != null &&
        address != null &&
        usbManager.deviceList.containsKey(address)
    }

  val connectedDeviceName: String?
    get() = connectedDevice?.productName ?: connectedDevice?.deviceName

  val connectedDeviceAddress: String?
    get() = connectedDevice?.deviceName

  val hasAnyUsbPrinter: Boolean
    get() = usbManager.deviceList.values.any { isUsbPrinter(it) }

  val allPrinterPermissionsGranted: Boolean
    get() = usbManager.deviceList.values
      .filter { isUsbPrinter(it) }
      .all { usbManager.hasPermission(it) }

  fun hasPermissionForDevice(deviceName: String): Boolean {
    val device = usbManager.deviceList[deviceName] ?: return false
    return usbManager.hasPermission(device)
  }

  fun hasDevice(deviceName: String): Boolean {
    return usbManager.deviceList.containsKey(deviceName)
  }

  // ═══════════════════════════════════════════════════════════
  // Discovery
  // ═══════════════════════════════════════════════════════════

  /**
   * Get list of connected USB devices that are printers.
   */
  fun getUsbPrinters(): List<PrinterInfo> {
    val devices = usbManager.deviceList.values
    Log.d(TAG, "USB device list: ${devices.size} devices total")

    // Log all USB devices for debugging
    devices.forEach { device ->
      Log.d(TAG, "  Device: ${device.deviceName} | product=${device.productName} " +
        "| vendor=0x${device.vendorId.toString(16)} | product=0x${device.productId.toString(16)} " +
        "| class=${device.deviceClass} | interfaces=${device.interfaceCount}")
    }

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
      if (device == null) {
        Log.e(TAG, "USB device not found: $deviceName. Available: ${usbManager.deviceList.keys}")
        throw IOException("USB device not found: $deviceName")
      }

      Log.i(TAG, "Attempting connection to: ${device.productName} ($deviceName) " +
        "vendor=0x${device.vendorId.toString(16)} product=0x${device.productId.toString(16)}")

      // Check/request permission
      if (!usbManager.hasPermission(device)) {
        Log.i(TAG, "Requesting USB permission for $deviceName...")
        val granted = requestPermission(device)
        if (!granted) {
          throw IOException("USB permission denied for $deviceName")
        }
        Log.i(TAG, "USB permission granted for $deviceName ✅")
      } else {
        Log.d(TAG, "USB permission already granted for $deviceName")
      }

      // Find printer interface and OUT bulk endpoint
      var printerInterface: UsbInterface? = null
      var outEndpoint: UsbEndpoint? = null

      // Priority 1: Look for printer class interface (class 7)
      for (i in 0 until device.interfaceCount) {
        val iface = device.getInterface(i)
        Log.d(TAG, "  Interface $i: class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} " +
          "protocol=${iface.interfaceProtocol} endpoints=${iface.endpointCount}")

        if (iface.interfaceClass == USB_CLASS_PRINTER) {
          for (j in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(j)
            Log.d(TAG, "    Endpoint $j: type=${ep.type} direction=${ep.direction} maxPacket=${ep.maxPacketSize}")
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT) {
              printerInterface = iface
              outEndpoint = ep
              break
            }
          }
          if (outEndpoint != null) break
        }
      }

      // Priority 2: Look for any interface with bulk OUT endpoint
      if (outEndpoint == null) {
        Log.d(TAG, "No printer-class interface found, looking for bulk OUT on any interface...")
        for (i in 0 until device.interfaceCount) {
          val iface = device.getInterface(i)
          for (j in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(j)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT) {
              printerInterface = iface
              outEndpoint = ep
              Log.d(TAG, "Found bulk OUT on interface $i endpoint $j")
              break
            }
          }
          if (outEndpoint != null) break
        }
      }

      if (printerInterface == null || outEndpoint == null) {
        throw IOException("No suitable bulk OUT endpoint found on $deviceName")
      }

      // Open connection — may need to re-request permission on some devices
      var conn = usbManager.openDevice(device)
      if (conn == null) {
        // openDevice can return null even when hasPermission() is true on some devices
        // Force re-request permission and try again
        Log.w(TAG, "openDevice returned null despite hasPermission=true, re-requesting permission...")
        val granted = requestPermission(device)
        if (!granted) {
          throw IOException("USB permission denied for $deviceName on retry")
        }
        conn = usbManager.openDevice(device)
        if (conn == null) {
          throw IOException("Failed to open USB device $deviceName after permission retry (openDevice returned null)")
        }
      }

      // Try to claim the found interface — use force=true to detach kernel driver
      var claimed = conn.claimInterface(printerInterface, true)
      if (!claimed) {
        Log.w(TAG, "claimInterface failed on first try, waiting and retrying...")
        Thread.sleep(500)
        claimed = conn.claimInterface(printerInterface, true)
      }

      // If still not claimed, try to claim ANY interface with a bulk OUT endpoint
      if (!claimed) {
        Log.w(TAG, "Claim failed on preferred interface, trying all interfaces...")
        for (i in 0 until device.interfaceCount) {
          val iface = device.getInterface(i)
          if (conn.claimInterface(iface, true)) {
            // Find bulk OUT on this interface
            for (j in 0 until iface.endpointCount) {
              val ep = iface.getEndpoint(j)
              if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                  ep.direction == UsbConstants.USB_DIR_OUT) {
                printerInterface = iface
                outEndpoint = ep
                claimed = true
                Log.i(TAG, "Successfully claimed fallback interface $i with bulk OUT endpoint")
                break
              }
            }
            if (claimed) break
            // Release if no suitable endpoint
            conn.releaseInterface(iface)
          }
        }
      }

      if (!claimed) {
        conn.close()
        throw IOException("Failed to claim any USB interface on $deviceName (interface may be in use)")
      }

      connection = conn
      usbInterface = printerInterface
      endpoint = outEndpoint
      connectedDevice = device

      // Send a quick ESC/POS init command to verify the connection works
      val initCmd = byteArrayOf(0x1B, 0x40) // ESC @ = Initialize printer
      val initResult = conn.bulkTransfer(outEndpoint, initCmd, initCmd.size, TRANSFER_TIMEOUT_MS)
      Log.i(TAG, "Init command result: $initResult (expected ${initCmd.size})")

      Log.i(TAG, "Connected to USB printer: ${device.productName} ($deviceName) ✅ " +
        "endpoint maxPacket=${outEndpoint!!.maxPacketSize}")
      return@withContext true

    } catch (e: Exception) {
      Log.e(TAG, "USB connection failed: ${e.message}", e)
      disconnect()
      return@withContext false
    }
  }

  /**
   * Request USB permission from the user.
   * Times out after PERMISSION_TIMEOUT_MS if the user doesn't respond.
   */
  private suspend fun requestPermission(device: UsbDevice): Boolean {
    return withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
      suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
          override fun onReceive(ctx: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
              try { context.unregisterReceiver(this) } catch (_: Exception) {}
              val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
              Log.d(TAG, "USB permission result: granted=$granted")
              if (cont.isActive) cont.resume(granted)
            }
          }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          // On Android 13+, use RECEIVER_EXPORTED so we can receive the
          // system's USB permission response broadcast
          context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
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
    } ?: run {
      Log.e(TAG, "USB permission request timed out after ${PERMISSION_TIMEOUT_MS}ms")
      false
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
          Log.e(TAG, "USB bulk transfer failed at offset $offset (transferred=$transferred)")
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
