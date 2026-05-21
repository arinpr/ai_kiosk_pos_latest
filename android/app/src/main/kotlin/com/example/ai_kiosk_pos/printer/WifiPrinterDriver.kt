package com.example.ai_kiosk_pos.printer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * WiFi/Network printer driver.
 *
 * Connects to network thermal printers using raw TCP socket on port 9100
 * (the standard ESC/POS raw print port).
 */
class WifiPrinterDriver {

  companion object {
    private const val TAG = "WifiPrinter"
    private const val DEFAULT_PORT = 9100
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val WRITE_TIMEOUT_MS = 5000
  }

  private var socket: Socket? = null
  private var inputStream: InputStream? = null
  private var outputStream: OutputStream? = null
  private var _connectedAddress: String? = null
  private var _connectedName: String? = null
  @Volatile private var lastRealtimeStatus: Map<String, Any> =
    PrinterHardwareStatus.unavailable("not_checked", "not_checked")

  val isConnected: Boolean
    get() = socket?.isConnected == true && socket?.isClosed == false

  val isConnectionHealthy: Boolean
    get() = isConnected && outputStream != null

  val connectedDeviceName: String?
    get() = _connectedName

  val connectedDeviceAddress: String?
    get() = _connectedAddress

  val realtimeStatus: Map<String, Any>
    get() = lastRealtimeStatus

  /**
   * Verify a live TCP socket. Socket.isConnected can remain true after a
   * printer is powered off, so status polling needs one active probe.
   */
  suspend fun verifyConnection(): Boolean = withContext(Dispatchers.IO) {
    val sock = socket
    if (sock == null || sock.isClosed || outputStream == null) {
      return@withContext false
    }
    try {
      sock.sendUrgentData(0xFF)
      true
    } catch (e: IOException) {
      Log.w(TAG, "WiFi printer health probe failed: ${e.message}")
      disconnect()
      false
    } catch (e: Exception) {
      Log.w(TAG, "WiFi printer health probe failed: ${e.message}")
      disconnect()
      false
    }
  }

  /**
   * Best-effort LAN discovery for ESC/POS network printers on port 9100.
   * Keeps timeout short so the printer popup is not blocked for long.
   */
  suspend fun scanNetworkPrinters(): List<PrinterInfo> = withContext(Dispatchers.IO) {
    val localAddress = getLocalIpv4Address() ?: return@withContext emptyList()
    val parts = localAddress.hostAddress?.split(".") ?: return@withContext emptyList()
    if (parts.size != 4) return@withContext emptyList()

    val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
    val self = parts[3].toIntOrNull()

    withTimeoutOrNull(2500) {
      coroutineScope {
        (1..254)
          .filter { it != self }
          .map { hostSuffix ->
            async(Dispatchers.IO) {
              val host = "$prefix.$hostSuffix"
              if (canReachPrinterPort(host, DEFAULT_PORT)) {
                PrinterInfo(
                  name = "Network Printer ($host)",
                  address = "$host:$DEFAULT_PORT",
                  type = "ethernet",
                  isPrinter = true,
                  isConnected = "$host:$DEFAULT_PORT" == connectedDeviceAddress && isConnected
                )
              } else {
                null
              }
            }
          }
          .awaitAll()
          .filterNotNull()
          .sortedBy { it.address }
      }
    } ?: emptyList()
  }

  /**
   * Connect to a WiFi printer by IP address.
   * Address format: "ip:port" or just "ip" (defaults to port 9100).
   */
  suspend fun connect(address: String): Boolean = withContext(Dispatchers.IO) {
    try {
      disconnect()

      val parts = address.split(":")
      val host = parts[0].trim()
      if (host.isBlank() || parts.size > 2) {
        Log.e(TAG, "Invalid WiFi printer address: $address")
        return@withContext false
      }
      val port = if (parts.size > 1) {
        parts[1].trim().toIntOrNull() ?: run {
          Log.e(TAG, "Invalid WiFi printer port in address: $address")
          return@withContext false
        }
      } else {
        DEFAULT_PORT
      }
      val normalizedAddress = "$host:$port"

      Log.i(TAG, "Connecting to WiFi printer at $host:$port...")

      val sock = Socket()
      sock.soTimeout = WRITE_TIMEOUT_MS
      sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

      socket = sock
      inputStream = sock.getInputStream()
      outputStream = sock.getOutputStream()
      _connectedAddress = normalizedAddress
      _connectedName = "WiFi Printer ($host)"

      Log.i(TAG, "Connected to WiFi printer at $host:$port ✅")
      return@withContext true

    } catch (e: Exception) {
      Log.e(TAG, "WiFi printer connection failed: ${e.message}", e)
      disconnect()
      return@withContext false
    }
  }

  /**
   * Disconnect from the WiFi printer.
   */
  fun disconnect() {
    try { outputStream?.close() } catch (_: Exception) {}
    try { inputStream?.close() } catch (_: Exception) {}
    try { socket?.close() } catch (_: Exception) {}
    inputStream = null
    outputStream = null
    socket = null
    _connectedAddress = null
    _connectedName = null
    Log.d(TAG, "WiFi printer disconnected")
  }

  private fun canReachPrinterPort(host: String, port: Int): Boolean {
    return try {
      Socket().use { sock ->
        sock.connect(InetSocketAddress(host, port), 180)
        true
      }
    } catch (_: Exception) {
      false
    }
  }

  private fun getLocalIpv4Address(): Inet4Address? {
    return try {
      NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Send raw ESC/POS bytes to the connected WiFi printer.
   */
  suspend fun print(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
    val stream = outputStream
    val addr = _connectedAddress

    // Auto-reconnect if connection was lost
    if (!isConnected && addr != null) {
      Log.w(TAG, "WiFi connection lost, attempting reconnect to $addr...")
      val reconnected = connect(addr)
      if (!reconnected) {
        Log.e(TAG, "WiFi reconnect failed")
        return@withContext false
      }
    }

    val activeStream = outputStream
    if (activeStream == null || !isConnected) {
      Log.e(TAG, "No active WiFi printer connection")
      return@withContext false
    }

    try {
      // Write in chunks so slower network print servers do not drop data.
      val chunkSize = 1024
      var offset = 0
      while (offset < data.size) {
        val end = minOf(offset + chunkSize, data.size)
        activeStream.write(data, offset, end - offset)
        activeStream.flush()
        offset = end
        if (offset < data.size) Thread.sleep(20)
      }

      Log.i(TAG, "WiFi print data sent: ${data.size} bytes ✅")
      refreshRealtimeStatus("after print")
      return@withContext true

    } catch (e: IOException) {
      Log.e(TAG, "WiFi print failed: ${e.message}", e)
      disconnect()
      return@withContext false
    }
  }

  suspend fun refreshRealtimeStatus(reason: String = "poll"): Map<String, Any> = withContext(Dispatchers.IO) {
    val stream = outputStream
    val input = inputStream
    if (stream == null || input == null || !isConnectionHealthy) {
      return@withContext PrinterHardwareStatus.unavailable(reason, "not_connected").also {
        lastRealtimeStatus = it
      }
    }

    val labels = listOf(1 to "printer", 2 to "offline", 3 to "error", 4 to "paper")
    val statusBytes = mutableMapOf<Int, Int>()
    for ((statusType, _) in labels) {
      val value = queryRealtimeStatus(stream, input, statusType)
      if (value != null) {
        statusBytes[statusType] = value
      }
    }

    if (statusBytes.isEmpty()) {
      return@withContext PrinterHardwareStatus.unavailable(reason, "no_escpos_dle_eot_response").also {
        lastRealtimeStatus = it
      }
    }

    val raw = labels.joinToString(" ") { (statusType, label) ->
      val value = statusBytes[statusType]
      "$label=${value?.let { String.format("0x%02X", it) } ?: "none"}"
    }
    Log.i(TAG, "WiFi realtime status ($reason): $raw")
    Log.i(TAG, "WiFi realtime decoded ($reason): ${PrinterHardwareStatus.decodeMessage(statusBytes)}")
    PrinterHardwareStatus.fromRealtimeBytes(reason, statusBytes).also {
      lastRealtimeStatus = it
    }
  }

  private fun queryRealtimeStatus(
    stream: OutputStream,
    input: InputStream,
    statusType: Int
  ): Int? {
    return try {
      while (input.available() > 0) {
        input.read()
      }

      stream.write(byteArrayOf(0x10, 0x04, statusType.toByte()))
      stream.flush()

      val deadline = System.currentTimeMillis() + 350L
      while (System.currentTimeMillis() < deadline) {
        if (input.available() > 0) {
          return input.read().takeIf { it >= 0 }?.and(0xFF)
        }
        Thread.sleep(25L)
      }
      null
    } catch (e: Exception) {
      Log.w(TAG, "WiFi realtime status query $statusType failed: ${e.message}")
      null
    }
  }
}
