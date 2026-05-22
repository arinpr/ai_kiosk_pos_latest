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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
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
    private const val ENABLE_BLUETOOTH_REALTIME_STATUS = false
    /** Standard SPP UUID for serial Bluetooth communication */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    /** Common Bluetooth device classes for printers */
    private val PRINTER_DEVICE_CLASSES = setOf(
      0x0680, // Imaging: Printer
      0x0600, // Imaging: Uncategorized
    )

    /**
     * If the socket has been idle for longer than this, proactively
     * disconnect+reconnect BEFORE writing. KPC307-class SPP printers
     * silently drop the RFCOMM channel after ~10–15 s idle but Android
     * does not know — the next write throws `Broken pipe` mid-chunk.
     * Rotating ahead of time hides the failure from the user (no error
     * tone, no red light, no 3 s retry wait).
     */
    private const val IDLE_ROTATE_MS = 5_000L

    /** Quick post-broken-pipe pause before tearing the dead socket down. */
    private const val BROKEN_PIPE_PRE_RECONNECT_MS = 400L

    /** Settle time after a fresh post-broken-pipe connect, before retry write. */
    private const val BROKEN_PIPE_POST_RECONNECT_MS = 400L

    // ── ESC/POS flow-control bytes ───────────────────────────────────
    //
    // Many SPP thermal printers (KPC307, GP-58, Zjiang clones, etc.)
    // implement *software* flow-control on the serial channel:
    //   • XOFF (0x13 / DC3) → "stop sending, my buffer is filling up"
    //   • XON  (0x11 / DC1) → "buffer drained, resume sending"
    //
    // If the host ignores XOFF and keeps writing, the printer's RFCOMM
    // peer eventually drops the socket with `IOException: Broken pipe`
    // mid-write (we used to see this at offset ~5,888 B of a 16 KB
    // bitmap receipt). The official Star Micronics ESC/POS reference
    // and the B4X / StackOverflow community both confirm that XOFF/XON
    // *is* the correct backpressure signal for these printers.
    private const val XOFF_BYTE = 0x13
    private const val XON_BYTE = 0x11

    /**
     * If we've been paused on XOFF for longer than this and no XON has
     * arrived, resume writing anyway. Some firmwares send XOFF but never
     * the matching XON; without a timeout the print would hang forever.
     */
    private const val XOFF_TIMEOUT_MS = 4_000L

    /**
     * Post-write settle time (DantSu/ESCPOS-ThermalPrinter-Android
     * formula: `data.length / 16 + addWaitingTime` milliseconds). The
     * physical print head is still moving after `write()` + `flush()`
     * return — closing the socket too soon causes the tail of long
     * receipts to vanish (Android 11+ bug, see DantSu issue #184).
     *
     * Clamped to a reasonable range; the per-byte component dominates
     * for large bitmap receipts but adds ~500 ms even for tiny test
     * prints so the printer always has time to finish.
     */
    private const val POST_WRITE_BASE_MS = 500L
    private const val POST_WRITE_PER_BYTE_DIVISOR = 16
    private const val POST_WRITE_MAX_MS = 4_000L
  }

  /**
   * Wall-clock of the last *successful* outbound activity on this socket
   * (connect or `writeChunks`). Used by [print] to decide whether to
   * silently rotate before writing.
   */
  @Volatile private var lastActivityAt: Long = 0L

  // ── XON/XOFF flow-control state ──────────────────────────────────
  /** True while we've received XOFF and are waiting for XON. */
  @Volatile private var xoffPaused: Boolean = false
  /** Timestamp of the most recent XOFF, used to enforce XOFF_TIMEOUT_MS. */
  @Volatile private var xoffStartedAt: Long = 0L
  /** Stats: how many XOFF / XON bytes we observed (mostly diagnostic). */
  @Volatile private var xoffCount: Long = 0L
  @Volatile private var xonCount: Long = 0L

  /**
   * Background coroutine that drains the printer's input stream and
   * watches for XOFF/XON bytes. Owns the socket's inputStream for the
   * lifetime of a connection; cancelled on [disconnect].
   */
  private var flowControlJob: Job? = null

  /** Long-lived scope for the flow-control reader. */
  private val driverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var socket: BluetoothSocket? = null
  private var inputStream: InputStream? = null
  private var outputStream: OutputStream? = null
  private var connectedDevice: BluetoothDevice? = null
  @Volatile private var lastRealtimeStatus: Map<String, Any> =
    realtimeUnavailable("not_checked", "not_checked")

  val isConnected: Boolean
    get() = socket != null && outputStream != null

  /** socket.isConnected is unreliable on many SPP thermal printers — use open streams instead. */
  val isConnectionHealthy: Boolean
    get() = isBluetoothEnabled && socket != null && outputStream != null

  val connectedDeviceName: String?
    get() = connectedDevice?.name

  val connectedDeviceAddress: String?
    get() = connectedDevice?.address

  val realtimeStatus: Map<String, Any>
    get() = lastRealtimeStatus

  suspend fun refreshRealtimeStatus(reason: String = "poll"): Map<String, Any> = withContext(Dispatchers.IO) {
    if (!ENABLE_BLUETOOTH_REALTIME_STATUS) {
      return@withContext markRealtimeStatusDisabled(reason)
    }
    if (!isConnectionHealthy) {
      return@withContext realtimeUnavailable(reason, "not_connected").also {
        lastRealtimeStatus = it
      }
    }
    queryAndLogRealtimeStatus(reason)
  }

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

      if (isConnectionHealthy && connectedDeviceAddress.equals(address, ignoreCase = true)) {
        Log.d(TAG, "Already connected to $address; reusing existing Bluetooth socket")
        return@withContext true
      }

      // Disconnect a different or stale connection first.
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
      inputStream = btSocket.inputStream
      outputStream = btSocket.outputStream
      connectedDevice = device
      lastActivityAt = System.currentTimeMillis()

      // Reset flow-control state and start the background reader so we
      // catch XOFF/XON immediately on the new socket.
      xoffPaused = false
      xoffStartedAt = 0L
      xoffCount = 0L
      xonCount = 0L
      startFlowControlReader()

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
    // Stop the flow-control reader BEFORE closing the streams so it
    // doesn't log a spurious "stream closed" error.
    stopFlowControlReader()
    try {
      inputStream?.close()
    } catch (_: Exception) {}
    try {
      outputStream?.close()
    } catch (_: Exception) {}
    try {
      socket?.close()
    } catch (_: Exception) {}
    inputStream = null
    outputStream = null
    socket = null
    connectedDevice = null
    xoffPaused = false
    Log.d(TAG, "Disconnected")
  }

  // ═══════════════════════════════════════════════════════════
  // XON/XOFF flow control
  // ═══════════════════════════════════════════════════════════

  /**
   * Start a background coroutine that continuously reads from the
   * printer's inputStream and watches for XOFF / XON bytes.
   *
   * KPC307-class printers send XOFF (0x13) when their on-device receive
   * buffer is about to overflow, and XON (0x11) when it has drained.
   * The host (us) is expected to stop writing on XOFF and resume on
   * XON. If we ignore XOFF, the printer's RFCOMM peer eventually drops
   * the socket with `IOException: Broken pipe` mid-write.
   *
   * Any other byte the printer sends (e.g. ESC/POS realtime status
   * responses) is logged and discarded — we don't actively poll status
   * during prints (see ENABLE_BLUETOOTH_REALTIME_STATUS).
   */
  private fun startFlowControlReader() {
    flowControlJob?.cancel()
    val input = inputStream ?: return
    val deviceLabel = connectedDeviceName ?: connectedDeviceAddress ?: "printer"
    flowControlJob = driverScope.launch {
      val buf = ByteArray(64)
      Log.d(TAG, "Flow control reader started ($deviceLabel)")
      try {
        while (isActive) {
          val avail = try { input.available() } catch (_: Exception) { -1 }
          if (avail < 0) break  // stream closed

          if (avail > 0) {
            val read = try {
              input.read(buf, 0, minOf(avail, buf.size))
            } catch (e: IOException) {
              Log.d(TAG, "Flow control reader: input closed (${e.message})")
              break
            }
            if (read <= 0) break

            for (i in 0 until read) {
              when (val b = buf[i].toInt() and 0xFF) {
                XOFF_BYTE -> {
                  if (!xoffPaused) {
                    xoffPaused = true
                    xoffStartedAt = System.currentTimeMillis()
                    Log.d(TAG, "⏸️ Flow control: XOFF — pausing writes (buffer full)")
                  }
                  xoffCount++
                }
                XON_BYTE -> {
                  if (xoffPaused) {
                    val waited = System.currentTimeMillis() - xoffStartedAt
                    Log.d(TAG, "▶️ Flow control: XON — resuming writes (paused ${waited}ms)")
                  }
                  xoffPaused = false
                  xonCount++
                }
                else -> {
                  // Non-flow-control byte (probably an unsolicited status
                  // response). Log it once but don't take action — we
                  // never asked for status during a print.
                  Log.v(TAG, "Flow control reader: ignoring 0x${"%02X".format(b)}")
                }
              }
            }
          } else {
            delay(20L)
          }
        }
      } catch (e: Exception) {
        Log.d(TAG, "Flow control reader stopped: ${e.message}")
      } finally {
        Log.d(TAG, "Flow control reader ended (xoff=$xoffCount xon=$xonCount)")
      }
    }
  }

  private fun stopFlowControlReader() {
    flowControlJob?.cancel()
    flowControlJob = null
    xoffPaused = false
  }

  /**
   * Block the current write thread while the printer has signalled
   * XOFF. Bounded by [XOFF_TIMEOUT_MS] so a misbehaving printer that
   * forgets to send XON cannot deadlock the print loop.
   *
   * @return how many milliseconds we waited (for logging).
   */
  private fun awaitXonIfPaused(): Long {
    if (!xoffPaused) return 0L
    val waitStart = System.currentTimeMillis()
    Log.d(TAG, "Writer: honouring XOFF, waiting for XON…")
    while (xoffPaused) {
      val waited = System.currentTimeMillis() - waitStart
      if (waited > XOFF_TIMEOUT_MS) {
        Log.w(TAG, "Writer: XOFF timeout (${waited}ms) — resuming optimistically")
        xoffPaused = false
        return waited
      }
      try { Thread.sleep(30L) } catch (_: InterruptedException) {}
    }
    return System.currentTimeMillis() - waitStart
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

    val stream = outputStream
    if (stream != null && isConnectionHealthy) {
      // ── Stale-socket rotation ──────────────────────────────────────
      //
      // The KPC307 (and most cheap RFCOMM thermal printers) silently
      // drops the SPP channel after roughly 10–15 s of idle time, but
      // Android keeps its end of the socket open. We have no way to
      // probe RFCOMM liveness without writing, so the conventional
      // path is "write → IOException → reconnect → retry" — which
      // takes ~4 s, blinks the printer's red LED, and trips our user-
      // visible "print failed" tone.
      //
      // Instead, if more than IDLE_ROTATE_MS has elapsed since our
      // last successful activity, we rotate the socket pre-emptively:
      // a fresh connect takes ~400 ms and the first write then
      // succeeds on the first try.
      val idleMs = System.currentTimeMillis() - lastActivityAt
      if (address != null && idleMs > IDLE_ROTATE_MS) {
        Log.d(
          TAG,
          "Idle ${idleMs}ms > ${IDLE_ROTATE_MS}ms — rotating BT socket before write to avoid broken pipe"
        )
        disconnect()
        if (!connect(address)) {
          Log.e(TAG, "Pre-write socket rotation failed")
          return@withContext false
        }
      } else {
        Log.d(TAG, "Using open Bluetooth socket (${connectedDeviceName}, ${data.size}B, idle=${idleMs}ms)")
      }
    } else if (address != null) {
      Log.w(TAG, "No open socket — connecting to $address before print (${data.size}B)...")
      if (!connect(address)) {
        Log.e(TAG, "Connect-before-print failed")
        return@withContext false
      }
      Thread.sleep(1_200L)
    } else {
      Log.e(TAG, "No Bluetooth printer connection or saved address")
      return@withContext false
    }

    val activeStream = outputStream
    if (activeStream == null || !isConnectionHealthy) {
      Log.e(TAG, "No healthy printer stream after connect check")
      return@withContext false
    }

    try {
      val startedAt = System.currentTimeMillis()
      writeChunks(activeStream, data)
      val elapsedMs = System.currentTimeMillis() - startedAt
      lastActivityAt = System.currentTimeMillis()
      Log.i(TAG, "Print data sent: ${data.size} bytes in ${elapsedMs}ms ✅")
      markRealtimeStatusDisabled("after print")
      return@withContext true
    } catch (e: IOException) {
      Log.e(TAG, "Print failed (${data.size}B): ${e.message}", e)
      disconnect()

      if (address == null) return@withContext false
      // No artificial payload ceiling here. Our trimmed bitmap is
      // ~24,7 KB; the previous 24,576-byte cap silently rejected
      // a valid receipt by 118 bytes. KPC307 firmware happily accepts
      // 32 KB writes; oversize protection is the writeChunks() chunk
      // sizing, not a hard skip.

      Log.w(TAG, "Waiting ${BROKEN_PIPE_PRE_RECONNECT_MS}ms before reconnect...")
      Thread.sleep(BROKEN_PIPE_PRE_RECONNECT_MS)

      if (!connect(address)) {
        Log.e(TAG, "Reconnect failed before print retry")
        return@withContext false
      }

      Thread.sleep(BROKEN_PIPE_POST_RECONNECT_MS)

      val retryStream = outputStream
      if (retryStream == null || !isConnectionHealthy) {
        Log.e(TAG, "No healthy stream after reconnect")
        return@withContext false
      }

      try {
        Log.d(TAG, "Retrying Bluetooth print after reconnect (${data.size}B)")
        writeChunks(retryStream, data)
        lastActivityAt = System.currentTimeMillis()
        Log.i(TAG, "Print retry succeeded: ${data.size} bytes ✅")
        markRealtimeStatusDisabled("after retry")
        return@withContext true
      } catch (retryError: IOException) {
        Log.e(TAG, "Print retry failed: ${retryError.message}", retryError)
        disconnect()
        return@withContext false
      }
    }
  }

  /**
   * Write the entire payload to the printer in a single
   * `OutputStream.write(byte[])` call followed by `flush()` and a
   * proportional post-write settle pause.
   *
   * ─── Why single-write instead of chunked writes ────────────────────
   *
   * Android's documented behaviour for `BluetoothOutputStream.write()`
   * is to BLOCK when the remote peer's RFCOMM credit pool is empty
   * (https://developer.android.com/develop/connectivity/bluetooth/
   * transfer-data — "write(byte[]) can block for flow control if the
   * remote device isn't calling read(byte[]) quickly enough"). The
   * kernel paces the actual L2CAP/RFCOMM segments based on real-time
   * credits from the printer — that is the correct flow-control layer,
   * and we should not duplicate it.
   *
   * Our previous chunked implementation (96-byte chunks + 110 ms
   * Thread.sleep) defeated this OS-level backpressure: each chunk was
   * small enough to fit in Android's internal BT queue, so write()
   * returned immediately and we never saw the printer's "slow down"
   * signal. The printer's small (~10 KB) on-device buffer then filled
   * silently and the firmware dropped the SPP socket
   * (`IOException: Broken pipe` at offset 5,888–10,560 B in logcat).
   *
   * ─── Why post-write sleep is critical ─────────────────────────────
   *
   * `write()` + `flush()` only mean "Android has handed the bytes to
   * the BT stack"; the print head is still mechanically moving when
   * the call returns. Closing the socket or starting a follow-up print
   * too soon truncates the receipt. DantSu's library (de facto Android
   * ESC/POS reference, 1.6k★) uses `data.length / 16 + addWaitingTime`
   * ms here — we adopt the same formula with safe clamps.
   *
   * ─── XON/XOFF reader is still running in the background ───────────
   *
   * The flow-control reader started in [startFlowControlReader] passively
   * watches the input stream for XOFF/XON during the entire socket
   * lifetime. The (rare) printers that do implement software flow
   * control will set [xoffPaused] and the kernel-level write will
   * naturally block until the printer drains, even though we no longer
   * insert explicit micro-pauses.
   */
  private fun writeChunks(stream: OutputStream, data: ByteArray) {
    val postWriteMs = (data.size / POST_WRITE_PER_BYTE_DIVISOR + POST_WRITE_BASE_MS)
      .coerceAtMost(POST_WRITE_MAX_MS)

    Log.d(
      TAG,
      "BT write start: ${data.size}B (single-write + post-sleep=${postWriteMs}ms)"
    )
    val started = System.currentTimeMillis()

    try {
      stream.write(data)
      stream.flush()
    } catch (e: IOException) {
      Log.e(
        TAG,
        "BT write aborted at single-write of ${data.size}B: ${e.message}"
      )
      throw e
    }

    val writeElapsedMs = System.currentTimeMillis() - started
    val throughputBps = if (writeElapsedMs > 0) {
      (data.size * 1000L / writeElapsedMs).toInt()
    } else {
      Int.MAX_VALUE
    }

    Log.d(
      TAG,
      "BT write done: ${data.size}B in ${writeElapsedMs}ms (${throughputBps}B/s), post-sleep=${postWriteMs}ms, xoff=$xoffCount xon=$xonCount"
    )
    Thread.sleep(postWriteMs)
  }

  private fun queryAndLogRealtimeStatus(reason: String): Map<String, Any> {
    val stream = outputStream
    val input = inputStream
    if (stream == null || input == null) {
      Log.w(TAG, "Printer realtime status skipped ($reason): streams unavailable")
      return realtimeUnavailable(reason, "streams_unavailable").also {
        lastRealtimeStatus = it
      }
    }

    val labels = listOf(
      1 to "printer",
      2 to "offline",
      3 to "error",
      4 to "paper"
    )
    val statusBytes = mutableMapOf<Int, Int>()

    for ((statusType, _) in labels) {
      val value = queryRealtimeStatus(stream, input, statusType)
      if (value != null) {
        statusBytes[statusType] = value
      }
    }

    if (statusBytes.isEmpty()) {
      Log.w(TAG, "Printer realtime status unavailable ($reason): no ESC/POS DLE EOT response")
      return realtimeUnavailable(reason, "no_escpos_dle_eot_response").also {
        lastRealtimeStatus = it
      }
    }

    val raw = labels.joinToString(" ") { (statusType, label) ->
      val value = statusBytes[statusType]
      "$label=${value?.let { String.format("0x%02X", it) } ?: "none"}"
    }
    Log.i(TAG, "Printer realtime status ($reason): $raw")
    Log.i(TAG, "Printer realtime decoded ($reason): ${decodeRealtimeStatus(statusBytes)}")

    return buildRealtimeStatus(reason, statusBytes).also {
      lastRealtimeStatus = it
    }
  }

  private fun markRealtimeStatusDisabled(reason: String): Map<String, Any> {
    return realtimeUnavailable(reason, "bluetooth_realtime_status_disabled").also {
      lastRealtimeStatus = it
      Log.i(TAG, "Printer realtime status skipped ($reason): disabled for Bluetooth stability")
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
      Log.w(TAG, "Printer realtime status query $statusType failed: ${e.message}")
      null
    }
  }

  private fun decodeRealtimeStatus(statusBytes: Map<Int, Int>): String {
    val issues = decodeRealtimeIssues(statusBytes)
    return if (issues.isEmpty()) "no_error_bits_reported" else issues.distinct().joinToString(",")
  }

  private fun decodeRealtimeIssues(statusBytes: Map<Int, Int>): List<String> {
    val issues = mutableListOf<String>()

    statusBytes[1]?.let { status ->
      if (status and 0x08 != 0) issues.add("offline")
    }
    statusBytes[2]?.let { status ->
      if (status and 0x04 != 0) issues.add("cover_open")
      if (status and 0x08 != 0) issues.add("feed_button_pressed")
      if (status and 0x20 != 0) issues.add("printing_stopped")
      if (status and 0x40 != 0) issues.add("mechanical_error")
    }
    statusBytes[3]?.let { status ->
      if (status and 0x08 != 0) issues.add("cutter_error")
      if (status and 0x20 != 0) issues.add("unrecoverable_error")
      if (status and 0x40 != 0) issues.add("auto_recoverable_error")
    }
    statusBytes[4]?.let { status ->
      if (status and 0x0C != 0) issues.add("paper_near_end")
      if (status and 0x60 != 0) issues.add("paper_end")
    }

    return issues.distinct()
  }

  private fun buildRealtimeStatus(reason: String, statusBytes: Map<Int, Int>): Map<String, Any> {
    val issues = decodeRealtimeIssues(statusBytes)
    val issueSet = issues.toSet()
    val raw = mapOf(
      "printer" to statusBytes[1].toHexStatus(),
      "offline" to statusBytes[2].toHexStatus(),
      "error" to statusBytes[3].toHexStatus(),
      "paper" to statusBytes[4].toHexStatus()
    )

    return mapOf(
      "available" to true,
      "reason" to reason,
      "checkedAt" to System.currentTimeMillis(),
      "raw" to raw,
      "issues" to issues,
      "message" to if (issues.isEmpty()) "no_error_bits_reported" else issues.joinToString(","),
      "paperEnd" to issueSet.contains("paper_end"),
      "paperNearEnd" to issueSet.contains("paper_near_end"),
      "coverOpen" to issueSet.contains("cover_open"),
      "cutterError" to issueSet.contains("cutter_error"),
      "printerOffline" to issueSet.contains("offline"),
      "mechanicalError" to issueSet.contains("mechanical_error"),
      "printingStopped" to issueSet.contains("printing_stopped"),
      "feedButtonPressed" to issueSet.contains("feed_button_pressed"),
      "unrecoverableError" to issueSet.contains("unrecoverable_error"),
      "autoRecoverableError" to issueSet.contains("auto_recoverable_error")
    )
  }

  private fun realtimeUnavailable(reason: String, message: String): Map<String, Any> {
    return mapOf(
      "available" to false,
      "reason" to reason,
      "checkedAt" to System.currentTimeMillis(),
      "raw" to mapOf<String, String>(),
      "issues" to listOf(message),
      "message" to message,
      "paperEnd" to false,
      "paperNearEnd" to false,
      "coverOpen" to false,
      "cutterError" to false,
      "printerOffline" to false,
      "mechanicalError" to false,
      "printingStopped" to false,
      "feedButtonPressed" to false,
      "unrecoverableError" to false,
      "autoRecoverableError" to false
    )
  }

  private fun Int?.toHexStatus(): String {
    return this?.let { String.format("0x%02X", it) } ?: ""
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
