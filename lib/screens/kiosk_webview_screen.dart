import 'dart:async';
import 'dart:math' as math;
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../config/app_config.dart';
import '../services/debug_log_service.dart';
import '../services/printer_service.dart';
import '../widgets/payment_overlays.dart';

/// Screen that displays the kiosk web application in a webview
class KioskWebViewScreen extends StatefulWidget {
  const KioskWebViewScreen({super.key, required this.kioskUrl, this.title});

  final String kioskUrl;
  final String? title;

  @override
  State<KioskWebViewScreen> createState() => _KioskWebViewScreenState();
}

class _KioskWebViewScreenState extends State<KioskWebViewScreen>
    with WidgetsBindingObserver {
  static const Duration _tapToPayTimeout = Duration(seconds: 120);
  static const Duration _dialogAutoHideDuration = Duration(seconds: 8);
  late final String kioskUrl = widget.kioskUrl;

  // Use centralized debug log service for all native communication
  final DebugLogService _debugService = DebugLogService();
  final PrinterService _printerService = PrinterService();

  InAppWebViewController? _webViewController;
  bool _isPageLoading = true;
  bool _isPaymentProcessing = false;
  bool _isMicRequesting = false;
  bool _showSplash = true;
  bool _splashMinElapsed = false;
  bool _pageLoaded = false;
  bool _nfcChecked = false;
  bool _hasPageLoadError = false;
  String _pageLoadErrorMessage = '';
  bool _showWebView = true;
  bool _nfcResumeCheckInFlight = false;
  Timer? _retryTimer;
  int _retryCount = 0;
  StreamSubscription<Map<String, dynamic>>? _printerStatusSub;
  StreamSubscription<bool>? _readerDisconnectSub;
  StreamSubscription<bool>? _readerConnectSub;
  String _lastPrewarmKey = '';
  // Track whether we've loaded successfully at least once in this session
  // (used to decide if resume should trigger a refresh).
  bool _hasLoadedOnce = false;
  // Epoch-seconds of the last time the page fully loaded.
  // Used to time-gate the resume reload (minimum 5 min between reloads).
  int _lastLoadedAt = 0;
  static const int _minReloadIntervalSeconds = 5 * 60; // 5 minutes

  /// Tracks whether we've had a successful payment → reader is connected.
  /// When true, skip the prepare dialog entirely for instant NFC screen.
  bool _readerConnected = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    // Log webview initialization
    _debugService.log('🌐 WebView screen initialized');

    // Listen for unexpected reader disconnections from native side
    // Resets _readerConnected so next payment shows prepare dialog
    _readerDisconnectSub = _debugService.readerDisconnectedStream.listen((_) {
      _readerConnected = false;
      _lastPrewarmKey = '';
      _debugService.log(
        '⚠️ Reader disconnected — next payment will re-prepare',
      );
    });

    // Listen for reader connected events from eagerPrepare background connect
    // When reader connects in background, first payment skips prepare dialog entirely
    _readerConnectSub = _debugService.readerConnectedStream.listen((_) {
      _readerConnected = true;
      _debugService.log('✅ Reader pre-connected — payments will be instant');
    });

    if (AppConfig.isPrinterEnabled) {
      _printerStatusSub = _debugService.printerStatusStream.listen((status) {
        unawaited(_emitPrinterStatusToWeb(status));
      });
    }

    // Eagerly prepare Tap to Pay in background for faster first payment.
    // Pre-initializes Terminal SDK + discovers + connects reader.
    _eagerPrepareTapToPay();

    Future.delayed(const Duration(seconds: 2), () {
      if (!mounted) return;
      _splashMinElapsed = true;
      _maybeHideSplash();
    });
  }

  /// Eagerly prepare Tap to Pay in background.
  /// Reads cached terminalBaseUrl + locationId from SharedPreferences
  /// (saved during the first payment from the webview) and pre-initializes
  /// Terminal SDK + discovers + connects reader so subsequent payments
  /// show the NFC screen in ~2-3s instead of 14-15s.
  Future<void> _eagerPrepareTapToPay() async {
    if (!AppConfig.isNfcEnabled) return;
    try {
      final prefs = await SharedPreferences.getInstance();
      final url = prefs.getString('cached_terminal_base_url') ?? '';
      final locId = prefs.getString('cached_stripe_location_id') ?? '';
      if (url.isEmpty || locId.isEmpty) {
        debugPrint(
          '⏭️ Eager TTP skipped: no cached terminal config yet (first launch)',
        );
        return;
      }
      _debugService.log('🚀 Eager TTP prepare starting (cached: $url)');
      await DebugLogService.channel.invokeMethod('eagerPrepare', {
        'terminalBaseUrl': url,
        'locationId': locId,
        'isSimulated': AppConfig.isTapToPaySimulated,
      });
      _debugService.log('✅ Eager TTP prepare dispatched');
    } catch (e) {
      debugPrint('⚠️ Eager TTP prepare failed (non-critical): $e');
    }
  }

  /// Cache terminal config from the webview payment request into SharedPreferences.
  /// Called on every payment so eager init works on next app launch.
  Future<void> _cacheTerminalConfig(
    String terminalBaseUrl,
    String locationId,
  ) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('cached_terminal_base_url', terminalBaseUrl);
      await prefs.setString('cached_stripe_location_id', locationId);
    } catch (e) {
      debugPrint('⚠️ Failed to cache terminal config: $e');
    }
  }

  Future<void> _cacheAndPrewarmTapToPayConfig(
    String terminalBaseUrl,
    String locationId,
  ) async {
    final normalizedUrl = terminalBaseUrl.trim().replaceFirst(
      RegExp(r'/$'),
      '',
    );
    final normalizedLocationId = locationId.trim();
    if (normalizedUrl.isEmpty || normalizedLocationId.isEmpty) return;

    await _cacheTerminalConfig(normalizedUrl, normalizedLocationId);

    final prewarmKey = '$normalizedUrl|$normalizedLocationId';
    if (_readerConnected || _lastPrewarmKey == prewarmKey) {
      return;
    }

    _lastPrewarmKey = prewarmKey;
    _debugService.log('🚀 Background Tap to Pay prepare dispatched');

    unawaited(
      DebugLogService.channel
          .invokeMethod('eagerPrepare', {
            'terminalBaseUrl': normalizedUrl,
            'locationId': normalizedLocationId,
            'isSimulated': AppConfig.isTapToPaySimulated,
          })
          .catchError((Object error) {
            if (_lastPrewarmKey == prewarmKey) {
              _lastPrewarmKey = '';
            }
            _debugService.log(
              '⚠️ Background Tap to Pay prepare failed: $error',
            );
            return null;
          }),
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _retryTimer?.cancel();
    _printerStatusSub?.cancel();
    _readerDisconnectSub?.cancel();
    _readerConnectSub?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkNfcOnResume();
      _checkDeveloperOptionsOnResume();
      if (AppConfig.isPrinterEnabled) {
        unawaited(_emitCurrentPrinterStatusToWeb());
      }
      // On resume, silently reload so any web deployment pushed while the
      // app was backgrounded is picked up. Cache-Control: no-cache header
      // tells the server to revalidate — if content unchanged the server
      // returns 304 and the cached JS/CSS is reused (fast).
      // Time-gated to 5 min minimum to avoid reloading on every app-switch.
      // Never reloads during an active payment.
      if (_hasLoadedOnce && !_isPaymentProcessing) {
        final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
        if (now - _lastLoadedAt >= _minReloadIntervalSeconds) {
          _debugService.log(
            '🔄 Resume reload: ${now - _lastLoadedAt}s since last load',
          );
          _webViewController?.loadUrl(
            urlRequest: URLRequest(
              url: WebUri(kioskUrl),
              headers: {
                'Cache-Control': 'no-cache, no-store',
                'Pragma': 'no-cache',
              },
            ),
          );
        }
      }
    }
  }

  void _maybeHideSplash() {
    if (!_showSplash) return;
    if (_splashMinElapsed && _pageLoaded) {
      setState(() => _showSplash = false);
    }
  }

  Map<String, dynamic> _safeMap(dynamic v) {
    if (v is Map) return Map<String, dynamic>.from(v);
    return <String, dynamic>{};
  }

  Future<void> _notifyWebStatus(Map<String, dynamic> payload) async {
    final controller = _webViewController;
    if (controller == null) return;
    final jsonPayload = jsonEncode(payload);
    await controller.evaluateJavascript(
      source:
          "window.onNativePaymentStatus && window.onNativePaymentStatus($jsonPayload);",
    );
  }

  Future<void> _emitPrinterStatusToWeb(Map<String, dynamic> status) async {
    final controller = _webViewController;
    if (controller == null) return;
    final normalized = _normalizePrinterStatus(status);
    final jsonPayload = jsonEncode(normalized);
    await controller.evaluateJavascript(
      source:
          "window.onPrinterStatusChanged && window.onPrinterStatusChanged($jsonPayload);",
    );
  }

  Future<void> _emitCurrentPrinterStatusToWeb() async {
    try {
      final status = await _printerService.getPrinterStatus();
      await _emitPrinterStatusToWeb(status);
    } catch (_) {
      await _emitPrinterStatusToWeb(_normalizePrinterStatus({}));
    }
  }

  Map<String, dynamic> _normalizePrinterStatus(Map<String, dynamic> status) {
    return {
      "connected": status["connected"] == true,
      "name": status["name"]?.toString() ?? "",
      "address": status["address"]?.toString() ?? "",
      "type": status["type"]?.toString() ?? "",
      "autoPrintEnabled": status["autoPrintEnabled"] is bool
          ? status["autoPrintEnabled"] as bool
          : true,
      "printCopies": status["printCopies"] is int
          ? status["printCopies"] as int
          : int.tryParse(status["printCopies"]?.toString() ?? "") ?? 1,
      "lastPrinterName": status["lastPrinterName"]?.toString() ?? "",
      "lastPrinterAddress": status["lastPrinterAddress"]?.toString() ?? "",
      "lastPrinterType": status["lastPrinterType"]?.toString() ?? "",
      "bluetoothEnabled": status["bluetoothEnabled"] == true,
      "bluetoothPermissionGranted":
          status["bluetoothPermissionGranted"] == true,
      "locationPermissionGranted": status["locationPermissionGranted"] == true,
      "usbPermissionGranted": status["usbPermissionGranted"] != false,
    };
  }

  bool _isPrinterCommand(dynamic type) {
    return const {
      "PRINT_RECEIPT",
      "PRINT_KOT",
      "PRINT_RAW",
      "SCAN_PRINTERS",
      "CONNECT_PRINTER",
      "DISCONNECT_PRINTER",
      "GET_PRINTER_STATUS",
      "TEST_PRINT",
      "UPDATE_PRINTER_SETTINGS",
      "GET_BLUETOOTH_STATUS",
      "REQUEST_BLUETOOTH_PERMISSION",
      "OPEN_BLUETOOTH_SETTINGS",
      "OPEN_APP_SETTINGS",
      "OPEN_USB_SETTINGS",
    }.contains(type);
  }

  Map<String, dynamic> _printerDisabledResponse() {
    const code = "PRINTER_DISCONNECTED";
    return {
      "ok": false,
      "code": code,
      "errorCode": code,
      "message": "Printer bridge is disabled for this app mode",
      "error": "Printer bridge is disabled for this app mode",
      "status": _normalizePrinterStatus({}),
    };
  }

  Map<String, dynamic> _printerFailureResponse({
    required String code,
    required String message,
    Map<String, dynamic>? status,
    String? type,
    dynamic details,
  }) {
    return {
      "ok": false,
      if (type != null) "type": type,
      "code": code,
      "errorCode": code,
      "message": message,
      "error": message,
      if (details != null) "details": details,
      "status": _normalizePrinterStatus(status ?? {}),
    };
  }

  Map<String, dynamic> _normalizePrinterBridgeResult(
    Map<String, dynamic> result, {
    String? type,
  }) {
    final status = _normalizePrinterStatus(_safeMap(result["status"]));
    final ok = result["ok"] == true;
    final code = result["errorCode"] ?? result["code"];
    final message = result["message"] ?? result["error"];
    return {
      "ok": ok,
      if (type != null) "type": type,
      if (result.containsKey("name")) "name": result["name"],
      if (result.containsKey("address")) "address": result["address"],
      if (result.containsKey("printerType"))
        "printerType": result["printerType"],
      if (result.containsKey("type") && type == null) "type": result["type"],
      if (!ok) "code": code ?? "PRINTER_DISCONNECTED",
      if (!ok) "errorCode": code ?? "PRINTER_DISCONNECTED",
      if (!ok) "message": message ?? "Printer operation failed",
      if (!ok) "error": message ?? "Printer operation failed",
      "status": status,
    };
  }

  Map<String, dynamic> _buildFallbackPayload({
    required String code,
    required String reason,
    String? message,
    dynamic details,
  }) {
    return {
      "ok": false,
      "type": "PAYMENT_RESULT",
      "reason": reason,
      "code": code,
      "errorCode": code,
      "message": message,
      "details": details,
      "canFallbackToCard": true,
      "fallbackAction": "USE_EXISTING_CARD_FLOW",
      "exitFlow": true,
    };
  }

  String _normalizeCode(dynamic code) {
    if (code == null) return "";
    return code.toString().trim().toUpperCase();
  }

  Future<void> _injectWebViewFixes(InAppWebViewController controller) async {
    const js = r'''
(function () {
  if (document.getElementById('kiosk-app-fixes')) return;
  const style = document.createElement('style');
  style.id = 'kiosk-app-fixes';
  style.textContent = `
    .safe-top {
      padding-top: 0 !important;
      min-height: 73px !important;
      height: auto !important;
      overflow: visible !important;
    }
  `;
  document.head.appendChild(style);
})();
''';
    await controller.evaluateJavascript(source: js);
  }

  Future<Map<String, dynamic>> _getNfcStatus() async {
    if (defaultTargetPlatform != TargetPlatform.android) {
      return {"supported": true, "enabled": true};
    }
    try {
      final res = await DebugLogService.channel.invokeMethod<dynamic>(
        "getNfcStatus",
      );
      if (res is Map) return Map<String, dynamic>.from(res);
    } on PlatformException {
      return {"supported": false, "enabled": false};
    }
    return {"supported": false, "enabled": false};
  }

  Future<void> _openNfcSettings() async {
    if (defaultTargetPlatform != TargetPlatform.android) return;
    try {
      await DebugLogService.channel.invokeMethod<void>("openNfcSettings");
    } on PlatformException {
      // Best-effort only; ignore failures.
    }
  }

  Future<Map<String, dynamic>> _getLocationStatus() async {
    if (defaultTargetPlatform != TargetPlatform.android) {
      return {"hasPermission": true, "enabled": true};
    }
    try {
      final res = await DebugLogService.channel.invokeMethod<dynamic>(
        "getLocationStatus",
      );
      if (res is Map) return Map<String, dynamic>.from(res);
    } on PlatformException {
      return {"hasPermission": false, "enabled": false};
    }
    return {"hasPermission": false, "enabled": false};
  }

  Future<void> _openLocationSettings() async {
    if (defaultTargetPlatform != TargetPlatform.android) return;
    try {
      await DebugLogService.channel.invokeMethod<void>("openLocationSettings");
    } on PlatformException {
      // Best-effort only; ignore failures.
    }
  }

  Future<bool> _checkLocationServicesEnabled() async {
    final status = await _getLocationStatus();
    final enabled = status["enabled"] == true;
    if (!enabled) {
      await _showLocationDisabledDialog();
      return false;
    }
    return true;
  }

  Future<Map<String, dynamic>> _getDeveloperOptionsStatus() async {
    if (defaultTargetPlatform != TargetPlatform.android) {
      return {"enabled": false};
    }
    try {
      final res = await DebugLogService.channel.invokeMethod<dynamic>(
        "getDeveloperOptionsStatus",
      );
      if (res is Map) return Map<String, dynamic>.from(res);
    } on PlatformException {
      return {"enabled": false};
    }
    return {"enabled": false};
  }

  Future<void> _openDeveloperSettings() async {
    if (defaultTargetPlatform != TargetPlatform.android) return;
    try {
      await DebugLogService.channel.invokeMethod<void>("openDeveloperSettings");
    } on PlatformException {
      // Best-effort only; ignore failures.
    }
  }

  void _scheduleDialogAutoHide(BuildContext dialogContext) {
    Future<void>.delayed(_dialogAutoHideDuration, () {
      if (!dialogContext.mounted) return;
      final route = ModalRoute.of(dialogContext);
      if (route?.isCurrent == true) {
        Navigator.of(dialogContext).pop();
      }
    });
  }

  Widget _buildAutoHideDialogContent(String message) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(message),
        const SizedBox(height: 12),
        const Text(
          "This alert closes automatically in 8 seconds.",
          style: TextStyle(color: Colors.black54, fontSize: 12),
        ),
      ],
    );
  }

  /// Returns true if developer options are OFF (safe to proceed).
  /// Shows a dialog with option to open Settings if developer options are ON.
  /// Skips the check entirely when TAP_TO_PAY_SIMULATED is true (testing mode).
  Future<bool> _checkDeveloperOptions() async {
    // Skip check in simulated mode so developers can test with dev options on
    if (AppConfig.isTapToPaySimulated) return true;

    final status = await _getDeveloperOptionsStatus();
    final enabled = status["enabled"] == true;
    if (enabled) {
      await _showDeveloperOptionsDialog();
      return false;
    }
    return true;
  }

  Future<void> _showDeveloperOptionsDialog() async {
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) {
        _scheduleDialogAutoHide(dialogContext);
        return AlertDialog(
          title: Row(
            children: const [
              Icon(Icons.developer_mode, color: Colors.orange),
              SizedBox(width: 8),
              Expanded(child: Text("Disable Developer Mode")),
            ],
          ),
          content: _buildAutoHideDialogContent(
            "Tap to Pay requires Developer Options to be disabled for security. "
            "Please go to Settings > Developer Options and turn it OFF, then try again.",
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text("Cancel"),
            ),
            TextButton(
              onPressed: () async {
                Navigator.of(dialogContext).pop();
                await _openDeveloperSettings();
              },
              child: const Text("Open Settings"),
            ),
          ],
        );
      },
    );
  }

  Future<void> _showLocationDisabledDialog() async {
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) {
        _scheduleDialogAutoHide(dialogContext);
        return AlertDialog(
          title: Row(
            children: const [
              Icon(Icons.location_off, color: Colors.orange),
              SizedBox(width: 8),
              Expanded(child: Text("Enable Location")),
            ],
          ),
          content: _buildAutoHideDialogContent(
            "Tap to Pay needs Location services enabled. Please enable Location in settings to continue.",
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text("Cancel"),
            ),
            TextButton(
              onPressed: () async {
                Navigator.of(dialogContext).pop();
                await _openLocationSettings();
              },
              child: const Text("Open Settings"),
            ),
          ],
        );
      },
    );
  }

  Future<void> _checkNfcOnStartup() async {
    if (!AppConfig.isNfcEnabled) return;
    if (_nfcChecked) return;
    _nfcChecked = true;
    final status = await _getNfcStatus();
    final supported = status["supported"] == true;
    final enabled = status["enabled"] == true;
    if (!supported) {
      await _notifyWebStatus({
        "ok": false,
        "type": "DEVICE_CAPABILITY",
        "code": "NFC_UNSUPPORTED",
        "errorCode": "NFC_UNSUPPORTED",
        "reason": "NFC_UNSUPPORTED",
        "canFallbackToCard": true,
        "fallbackAction": "USE_EXISTING_CARD_FLOW",
        "exitFlow": true,
      });
      return;
    }
    if (!enabled) {
      await _showNfcDisabledDialog();
    }
  }

  Future<void> _checkNfcOnResume() async {
    if (!AppConfig.isNfcEnabled) return;
    if (_nfcResumeCheckInFlight) return;
    _nfcResumeCheckInFlight = true;
    try {
      final status = await _getNfcStatus();
      final supported = status["supported"] == true;
      final enabled = status["enabled"] == true;
      if (!supported) {
        await _notifyWebStatus({
          "ok": false,
          "type": "DEVICE_CAPABILITY",
          "code": "NFC_UNSUPPORTED",
          "errorCode": "NFC_UNSUPPORTED",
          "reason": "NFC_UNSUPPORTED",
          "canFallbackToCard": true,
          "fallbackAction": "USE_EXISTING_CARD_FLOW",
          "exitFlow": true,
        });
        return;
      }
      if (!enabled) {
        await _showNfcDisabledDialog();
      }
    } finally {
      _nfcResumeCheckInFlight = false;
    }
  }

  /// Re-check developer options when returning from Settings.
  /// If user just disabled dev options, they can proceed with next payment.
  Future<void> _checkDeveloperOptionsOnResume() async {
    final status = await _getDeveloperOptionsStatus();
    final enabled = status["enabled"] == true;
    if (enabled) {
      _debugService.log('⚠️ Developer Options still enabled');
    }
  }

  Future<void> _showNfcDisabledDialog() async {
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) {
        _scheduleDialogAutoHide(dialogContext);
        return AlertDialog(
          title: Row(
            children: const [
              Icon(Icons.nfc, color: Colors.orange),
              SizedBox(width: 8),
              Expanded(child: Text("Enable NFC")),
            ],
          ),
          content: _buildAutoHideDialogContent(
            "Tap to Pay needs NFC. Please enable NFC in settings to continue.",
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text("Cancel"),
            ),
            TextButton(
              onPressed: () async {
                Navigator.of(dialogContext).pop();
                await _openNfcSettings();
              },
              child: const Text("Open Settings"),
            ),
          ],
        );
      },
    );
  }

  Future<void> _showPaymentErrorDialog({
    required String title,
    required String message,
    IconData icon = Icons.error_outline,
  }) async {
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) {
        _scheduleDialogAutoHide(dialogContext);
        return AlertDialog(
          title: Row(
            children: [
              Icon(icon, color: Colors.redAccent),
              const SizedBox(width: 8),
              Expanded(child: Text(title)),
            ],
          ),
          content: _buildAutoHideDialogContent(message),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text("OK"),
            ),
          ],
        );
      },
    );
  }

  Future<void> _showPaymentTimeoutDialog() {
    return _showPaymentErrorDialog(
      title: "Transaction Timed Out",
      message: "Tap to Pay timed out. Continue with card flow.",
      icon: Icons.timer_off_outlined,
    );
  }

  /// Shows an animated progress dialog while the TTP component downloads.
  /// Progress steps are driven by REAL native events (onTtpProgress) from MainActivity.
  /// A safety fallback timer advances steps if native events are delayed.
  Future<void> _showTtpPrepareDialog({
    required String terminalBaseUrl,
    required String locationId,
    required bool isSimulated,
  }) async {
    if (!mounted) return;

    // Steps map native step index → display message
    // Native sends step 0=Initializing, 1=Connecting, 2=Downloading, 3=Ready
    const stepMessages = [
      'Preparing Tap to Pay...',
      'Preparing Tap to Pay...',
      'Preparing Tap to Pay...',
      'Payment reader ready!',
    ];

    int currentStep = 0;
    String currentMessage = stepMessages[0];
    bool isDone = false;
    StateSetter? dialogSetState;

    // Start native prepare call in parallel (60s timeout for slow component downloads)
    Map<dynamic, dynamic> prepareResult = {'status': 'UNKNOWN'};
    final prepareFuture = DebugLogService.channel
        .invokeMethod('prepareTapToPay', {
          'terminalBaseUrl': terminalBaseUrl,
          'locationId': locationId,
          'isSimulated': isSimulated,
        })
        .timeout(
          const Duration(seconds: 60),
          onTimeout: () => {'status': 'TIMEOUT'},
        )
        .then((res) {
          if (res is Map) prepareResult = res;
          return res;
        })
        .catchError((e) {
          prepareResult = {'status': 'ERROR', 'error': e.toString()};
          return prepareResult;
        });

    // Listen to REAL native progress events from centralized service
    final progressSub = _debugService.ttpProgressStream.listen((event) {
      if (isDone || dialogSetState == null) return;
      final step = event['step'] as int? ?? currentStep;
      dialogSetState!(() {
        currentStep = math.min(step, stepMessages.length - 1);
        // Keep customer-facing copy stable; native can emit technical messages
        // like "Downloading..." that should not be shown in the UI.
        currentMessage = stepMessages[currentStep];
      });
    });

    // Safety fallback: if native events are slow, advance steps every 8s
    // This only kicks in if native hasn't sent an event for that step yet
    final fallbackTimer = Stream.periodic(const Duration(seconds: 8), (i) => i)
        .take(stepMessages.length - 1)
        .listen((i) {
          if (isDone || dialogSetState == null) return;
          final nextStep = i + 1;
          if (nextStep > currentStep) {
            // Only advance if native hasn't already moved us past this step
            dialogSetState!(() {
              currentStep = nextStep;
              currentMessage = stepMessages[nextStep];
            });
          }
        });

    // Show compact dialog (matching existing _buildLoadingOverlay style)
    showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) {
          dialogSetState = setState;
          const brandColor = Color(0xFFC2410C);
          return Dialog(
            backgroundColor: Colors.transparent,
            elevation: 0,
            child: Center(
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 16,
                ),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    colors: [Color(0xFFFFF2E9), Color(0xFFFFF8F4)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: BorderRadius.circular(16),
                  boxShadow: const [
                    BoxShadow(
                      color: Colors.black26,
                      blurRadius: 12,
                      offset: Offset(0, 6),
                    ),
                  ],
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2.5,
                            valueColor: AlwaysStoppedAnimation<Color>(
                              brandColor,
                            ),
                          ),
                        ),
                        const SizedBox(width: 10),
                        Flexible(
                          child: AnimatedSwitcher(
                            duration: const Duration(milliseconds: 300),
                            child: Text(
                              currentMessage,
                              key: ValueKey(currentMessage),
                              style: const TextStyle(
                                color: Colors.black87,
                                fontSize: 13,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    SizedBox(
                      width: 160,
                      child: LinearProgressIndicator(
                        value: (currentStep + 1) / stepMessages.length,
                        backgroundColor: const Color(0xFFF5D8C9),
                        valueColor: const AlwaysStoppedAnimation<Color>(
                          brandColor,
                        ),
                        minHeight: 4,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );

    try {
      // Wait for native call to complete
      await prepareFuture;
    } finally {
      isDone = true;
      progressSub.cancel();
      fallbackTimer.cancel();
    }

    // specific status check to avoid showing "Ready!" on failure
    final isSuccess = prepareResult['status'] == 'READY';

    // Show "Ready!" briefly then close ONLY if successful
    if (isSuccess && dialogSetState != null) {
      dialogSetState!(() {
        currentStep = stepMessages.length - 1;
        currentMessage = stepMessages.last;
      });
      await Future.delayed(const Duration(milliseconds: 700));
    }

    if (mounted && Navigator.of(context).canPop()) {
      Navigator.of(context).pop();
    }
  }

  /// Shows a modern tap-to-pay instruction overlay before the NFC screen.
  /// Auto-dismisses after 8 seconds, or immediately when user taps Continue.
  Future<void> _showTapToPayInstruction({
    required int amountCents,
    required String currency,
  }) async {
    if (!mounted) return;

    String amountStr = '';
    if (amountCents > 0) {
      final amount = amountCents / 100.0;
      final cur = currency.toLowerCase();
      final sym = switch (cur) {
        'gbp' => '£',
        'usd' => '\$',
        'eur' => '€',
        _ => '${currency.toUpperCase()} ',
      };
      amountStr = '$sym${amount.toStringAsFixed(2)}';
    }

    await showGeneralDialog(
      context: context,
      barrierDismissible: false,
      barrierColor: Colors.black.withValues(alpha: 0.6),
      transitionDuration: const Duration(milliseconds: 350),
      transitionBuilder: (ctx, anim, secondAnim, child) {
        return FadeTransition(
          opacity: CurvedAnimation(parent: anim, curve: Curves.easeOut),
          child: ScaleTransition(
            scale: Tween<double>(begin: 0.92, end: 1.0).animate(
              CurvedAnimation(parent: anim, curve: Curves.easeOutCubic),
            ),
            child: child,
          ),
        );
      },
      pageBuilder: (ctx, anim, secondAnim) {
        return TapToPayInstructionOverlay(
          amountStr: amountStr,
          deviceModel: 'SUNMI Flex 3',
          nfcHint: 'Hold Here',
          onDone: () {
            if (Navigator.of(ctx).canPop()) Navigator.of(ctx).pop();
          },
        );
      },
    );
  }

  Future<void> _showPaymentSuccessDialog({
    String? last4,
    String? cardBrand,
    int? amountCents,
    String? currency,
  }) async {
    if (!mounted) return;

    // Format amount
    String amountStr = '';
    if (amountCents != null && amountCents > 0) {
      final amount = amountCents / 100.0;
      final cur = (currency ?? 'gbp').toLowerCase();
      final currencySymbol = switch (cur) {
        'gbp' => '£',
        'usd' => '\$',
        'eur' => '€',
        _ => '${currency?.toUpperCase() ?? ''} ',
      };
      amountStr = '$currencySymbol${amount.toStringAsFixed(2)}';
    }
    final cardStr = (last4 != null && last4.isNotEmpty)
        ? '${cardBrand != null ? '${_formatBrand(cardBrand)} ' : ''}•••• $last4'
        : '';

    await showGeneralDialog(
      context: context,
      barrierDismissible: false,
      barrierColor: Colors.black54,
      transitionDuration: const Duration(milliseconds: 400),
      transitionBuilder: (ctx, anim, secondAnim, child) {
        return FadeTransition(
          opacity: CurvedAnimation(parent: anim, curve: Curves.easeOut),
          child: ScaleTransition(
            scale: CurvedAnimation(parent: anim, curve: Curves.elasticOut),
            child: child,
          ),
        );
      },
      pageBuilder: (ctx, anim, secondAnim) {
        return PaymentSuccessOverlay(
          amountStr: amountStr,
          cardStr: cardStr,
          onDone: () {
            if (Navigator.of(ctx).canPop()) Navigator.of(ctx).pop();
          },
        );
      },
    );
  }

  String _formatBrand(String brand) {
    switch (brand.toLowerCase()) {
      case 'visa':
        return 'Visa';
      case 'mastercard':
        return 'Mastercard';
      case 'amex':
        return 'Amex';
      case 'discover':
        return 'Discover';
      case 'jcb':
        return 'JCB';
      case 'unionpay':
        return 'UnionPay';
      default:
        return brand[0].toUpperCase() + brand.substring(1);
    }
  }

  Widget _buildLoadingOverlay() {
    if (_showSplash) return const SizedBox.shrink();

    // Only show for page load, payment processing (after TTP prep), or mic request
    final show = _isPageLoading || _isPaymentProcessing || _isMicRequesting;
    if (!show) return const SizedBox.shrink();

    final label = _isMicRequesting
        ? "Requesting microphone..."
        : (_isPaymentProcessing ? "Processing payment..." : "Loading...");

    final sublabel = "Please wait a moment";
    const brandColor = Color(0xFFC2410C);
    return Positioned.fill(
      child: Container(
        color: Colors.black.withValues(alpha: 0.18),
        child: Center(
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [Color(0xFFFFF2E9), Color(0xFFFFF8F4)],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              borderRadius: BorderRadius.circular(20),
              boxShadow: const [
                BoxShadow(
                  color: Colors.black26,
                  blurRadius: 12,
                  offset: Offset(0, 6),
                ),
              ],
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.restaurant_menu, color: brandColor, size: 28),
                const SizedBox(height: 12),
                const SizedBox(
                  width: 160,
                  child: LinearProgressIndicator(
                    minHeight: 6,
                    valueColor: AlwaysStoppedAnimation<Color>(brandColor),
                    backgroundColor: Color(0xFFF5D8C9),
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  label,
                  style: const TextStyle(
                    color: Colors.black87,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  sublabel,
                  style: const TextStyle(color: Colors.black54, fontSize: 13),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildInAppSplash() {
    if (!_showSplash) return const SizedBox.shrink();
    const bgColor = Color(0xFFF3F4F6);
    const logoColor = Color(0xFFC2410C);
    return Positioned.fill(
      child: Container(
        color: bgColor,
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: const [
              CircleAvatar(
                radius: 46,
                backgroundColor: logoColor,
                child: Icon(Icons.restaurant, color: Colors.white, size: 42),
              ),
              SizedBox(height: 18),
              KioskDotLoader(color: logoColor),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPageLoadError() {
    if (!_hasPageLoadError) return const SizedBox.shrink();
    const brandColor = Color(0xFFC2410C);
    return Positioned.fill(
      child: Container(
        color: Colors.white,
        padding: const EdgeInsets.symmetric(horizontal: 28),
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.wifi_off, color: brandColor, size: 56),
              const SizedBox(height: 16),
              const Text(
                "We could not load the kiosk",
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 8),
              Text(
                _pageLoadErrorMessage.isEmpty
                    ? "Please check the connection and try again."
                    : _pageLoadErrorMessage,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.black54, fontSize: 14),
              ),
              const SizedBox(height: 20),
              FilledButton(
                onPressed: () {
                  setState(() {
                    _hasPageLoadError = false;
                    _pageLoadErrorMessage = '';
                    _isPageLoading = true;
                    _showWebView = false;
                  });
                  _webViewController?.reload();
                },
                child: const Text("Retry"),
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Stack(
          children: [
            Offstage(
              offstage: !_showWebView,
              child: InAppWebView(
                initialUrlRequest: URLRequest(
                  url: WebUri(kioskUrl),
                  // no-cache header: tells the server to revalidate index.html
                  // on every launch. The hashed JS/CSS assets still cache
                  // correctly. When offline, LOAD_DEFAULT falls back to the
                  // local cache without needing a URL query param match.
                  headers: {
                    'Cache-Control': 'no-cache, no-store',
                    'Pragma': 'no-cache',
                  },
                ),
                initialSettings: InAppWebViewSettings(
                  javaScriptEnabled: true,
                  domStorageEnabled: true,
                  databaseEnabled: true,
                  mixedContentMode: MixedContentMode.MIXED_CONTENT_ALWAYS_ALLOW,
                  useWideViewPort: true,
                  loadWithOverviewMode: true,
                  // Allow cache fallback so the web app loads when offline.
                  // LOAD_DEFAULT uses cache only when network is unavailable.
                  cacheEnabled: true,
                  cacheMode: CacheMode.LOAD_DEFAULT,
                  clearCache: false,
                  mediaPlaybackRequiresUserGesture: false,
                  allowsInlineMediaPlayback: true,
                  disableContextMenu: true,
                  transparentBackground: false,
                ),
                onLoadStart: (controller, url) {
                  if (!mounted) return;
                  _pageLoaded = false;
                  setState(() {
                    _isPageLoading = true;
                    _hasPageLoadError = false;
                    _pageLoadErrorMessage = '';
                  });
                },
                onLoadStop: (controller, url) {
                  if (!mounted) return;
                  _pageLoaded = true;
                  _hasLoadedOnce = true;
                  _lastLoadedAt = DateTime.now().millisecondsSinceEpoch ~/ 1000;
                  _retryCount = 0;
                  _retryTimer?.cancel();
                  _maybeHideSplash();
                  setState(() {
                    _isPageLoading = false;
                    _showWebView = true;
                  });
                  _checkNfcOnStartup();
                  _injectWebViewFixes(controller);
                  if (AppConfig.isPrinterEnabled) {
                    unawaited(_emitCurrentPrinterStatusToWeb());
                  }
                },
                onReceivedServerTrustAuthRequest:
                    (controller, challenge) async {
                      return ServerTrustAuthResponse(
                        action: ServerTrustAuthResponseAction.PROCEED,
                      );
                    },
                onReceivedError: (controller, request, error) async {
                  if (!mounted) return;
                  // Ignore subframe errors or non-critical resource failures
                  if (request.isForMainFrame != true) return;

                  // CRITICAL: Never auto-retry during payment processing.
                  // When the Stripe NFC activity is shown, the Flutter activity
                  // goes to background and the webview may fire spurious errors.
                  // Reloading here would nuke the web app's payment state.
                  if (_isPaymentProcessing) {
                    debugPrint(
                      '⏭️ Ignoring webview error during payment: ${error.description}',
                    );
                    return;
                  }

                  _pageLoaded = true;
                  _maybeHideSplash();

                  // Auto-retry logic for connection issues
                  if (_retryCount < 5) {
                    _retryTimer?.cancel();
                    _retryTimer = Timer(const Duration(seconds: 5), () {
                      if (mounted && !_isPaymentProcessing) {
                        _webViewController?.reload();
                      }
                    });
                    _retryCount++;
                  }

                  setState(() {
                    _isPageLoading = false;
                    _hasPageLoadError = true;
                    _pageLoadErrorMessage =
                        "${error.description}\n(Retrying $_retryCount/5...)";
                    _showWebView = false;
                  });
                },
                onPermissionRequest: (controller, request) async {
                  final needsMic = request.resources.contains(
                    PermissionResourceType.MICROPHONE,
                  );
                  if (needsMic) {
                    debugPrint(
                      "WebView permission request: ${request.resources}",
                    );
                    if (mounted) {
                      setState(() => _isMicRequesting = true);
                    }
                    final granted = await DebugLogService.channel
                        .invokeMethod<bool>("requestMicrophonePermission");
                    if (mounted) {
                      setState(() => _isMicRequesting = false);
                    }
                    if (granted != true) {
                      return PermissionResponse(
                        resources: request.resources,
                        action: PermissionResponseAction.DENY,
                      );
                    }
                  }
                  return PermissionResponse(
                    resources: request.resources,
                    action: PermissionResponseAction.GRANT,
                  );
                },
                onWebViewCreated: (controller) {
                  _webViewController = controller;
                  // Cache is preserved for offline fallback (cacheMode: LOAD_DEFAULT).
                  controller.addJavaScriptHandler(
                    handlerName: "kioskBridge",
                    callback: (args) async {
                      final payload = (args.isNotEmpty)
                          ? _safeMap(args[0])
                          : {};
                      final type = payload["type"];

                      // Health ping for debugging
                      if (type == "PING") {
                        return {
                          "ok": true,
                          "pong": true,
                          "platform": "flutter",
                        };
                      }

                      // ── Printer Operations ──
                      if (_isPrinterCommand(type) &&
                          !AppConfig.isPrinterEnabled) {
                        return _printerDisabledResponse();
                      }

                      if (type == "PRINT_RECEIPT") {
                        try {
                          final orderData = Map<String, dynamic>.from(payload);
                          orderData.remove("type");
                          final copies = payload["copies"] is int
                              ? payload["copies"] as int
                              : null;
                          final result = await _printerService.printReceipt(
                            orderData,
                            copies: copies,
                          );
                          final status = _safeMap(result["status"]);
                          if (status.isNotEmpty) {
                            unawaited(_emitPrinterStatusToWeb(status));
                          }
                          return {
                            "ok": result["ok"] == true,
                            "type": "PRINT_RESULT",
                            if (result["ok"] != true)
                              "code": result["code"] ?? "PRINTER_DISCONNECTED",
                            if (result["ok"] != true)
                              "errorCode":
                                  result["errorCode"] ??
                                  result["code"] ??
                                  "PRINTER_DISCONNECTED",
                            if (result["ok"] != true)
                              "message":
                                  result["message"] ??
                                  result["error"] ??
                                  "Receipt print failed",
                            if (result["ok"] != true)
                              "error":
                                  result["error"] ??
                                  result["message"] ??
                                  "Receipt print failed",
                            "status": _normalizePrinterStatus(status),
                          };
                        } catch (e) {
                          final status = await _printerService
                              .getPrinterStatus();
                          unawaited(_emitPrinterStatusToWeb(status));
                          return _printerFailureResponse(
                            code: "PRINTER_DISCONNECTED",
                            message: e.toString(),
                            status: status,
                            type: "PRINT_RESULT",
                          );
                        }
                      }

                      if (type == "PRINT_KOT") {
                        try {
                          final orderData = Map<String, dynamic>.from(payload);
                          orderData.remove("type");
                          final result = await _printerService.printKot(
                            orderData,
                          );
                          final status = _safeMap(result["status"]);
                          if (status.isNotEmpty) {
                            unawaited(_emitPrinterStatusToWeb(status));
                          }
                          return {
                            "ok": result["ok"] == true,
                            "type": "PRINT_RESULT",
                            if (result["ok"] != true)
                              "code": result["code"] ?? "PRINTER_DISCONNECTED",
                            if (result["ok"] != true)
                              "errorCode":
                                  result["errorCode"] ??
                                  result["code"] ??
                                  "PRINTER_DISCONNECTED",
                            if (result["ok"] != true)
                              "message":
                                  result["message"] ??
                                  result["error"] ??
                                  "KOT print failed",
                            if (result["ok"] != true)
                              "error":
                                  result["error"] ??
                                  result["message"] ??
                                  "KOT print failed",
                            "status": _normalizePrinterStatus(status),
                          };
                        } catch (e) {
                          final status = await _printerService
                              .getPrinterStatus();
                          unawaited(_emitPrinterStatusToWeb(status));
                          return _printerFailureResponse(
                            code: "PRINTER_DISCONNECTED",
                            message: e.toString(),
                            status: status,
                            type: "PRINT_RESULT",
                          );
                        }
                      }

                      if (type == "PRINT_RAW") {
                        try {
                          final base64Data = payload["data"] as String?;
                          final copiesRaw = payload["copies"];
                          final copies = copiesRaw is int
                              ? copiesRaw
                              : copiesRaw is num
                              ? copiesRaw.toInt().clamp(1, 5)
                              : 1;
                          if (base64Data == null || base64Data.trim().isEmpty) {
                            final status = await _printerService
                                .getPrinterStatus();
                            return {
                              "ok": false,
                              "errorCode": "PRINTER_DISCONNECTED",
                              "message": "Missing 'data' field",
                              "status": _normalizePrinterStatus(status),
                            };
                          }
                          final receiptPayload = _safeMap(
                            payload["receiptPayload"],
                          );
                          final jobType = payload["jobType"]?.toString() ?? "raw";
                          _debugService.log(
                            'PRINT_RAW request received from WebView: '
                            'jobType=$jobType copies=$copies '
                            'base64Length=${base64Data.length} '
                            'hasReceiptPayload=${receiptPayload.isNotEmpty}',
                          );
                          if (receiptPayload.isNotEmpty) {
                            _debugService.log(
                              'PRINT_RAW receiptPayload keys=${receiptPayload.keys.join(",")} '
                              'orderNumber=${receiptPayload["orderNumber"] ?? "<none>"}',
                            );
                          }
                          final result = await _printerService.printRaw(
                            base64Data,
                            copies: copies,
                            jobType: jobType,
                            receiptPayload: jobType == "receipt" ? null : receiptPayload.isNotEmpty ? receiptPayload : null,
                          );
                          final status = _safeMap(result["status"]);
                          final normalizedStatus = status.isNotEmpty
                              ? _normalizePrinterStatus(status)
                              : _normalizePrinterStatus(
                                  await _printerService.getPrinterStatus(),
                                );
                          if (result["ok"] == true) {
                            // Native printRaw already emitStatus — skip duplicate push.
                            return {"ok": true, "status": normalizedStatus};
                          }
                          unawaited(_emitPrinterStatusToWeb(normalizedStatus));
                          return {
                            "ok": false,
                            "errorCode":
                                result["errorCode"] ??
                                result["code"] ??
                                "PRINTER_DISCONNECTED",
                            "message":
                                result["message"] ??
                                result["error"] ??
                                "Raw print failed",
                            "status": normalizedStatus,
                          };
                        } catch (e) {
                          final status = await _printerService
                              .getPrinterStatus();
                          final normalizedStatus = _normalizePrinterStatus(
                            status,
                          );
                          unawaited(_emitPrinterStatusToWeb(normalizedStatus));
                          return {
                            "ok": false,
                            "errorCode": "PRINTER_DISCONNECTED",
                            "message": e.toString(),
                            "status": normalizedStatus,
                          };
                        }
                      }

                      if (type == "SCAN_PRINTERS") {
                        try {
                          final result = await _printerService
                              .scanPrintersDetailed();
                          return {
                            "ok": result["ok"] != false,
                            "type": "SCAN_RESULT",
                            "printers": result["printers"] ?? [],
                            "bluetoothPermissionGranted":
                                result["bluetoothPermissionGranted"] == true,
                            "bluetoothEnabled":
                                result["bluetoothEnabled"] == true,
                            "bluetoothAvailable":
                                result["bluetoothAvailable"] != false,
                            if (result["ok"] == false)
                              "code":
                                  result["code"] ??
                                  result["errorCode"] ??
                                  "NO_PAIRED_PRINTERS",
                            if (result["ok"] == false)
                              "errorCode":
                                  result["errorCode"] ??
                                  result["code"] ??
                                  "NO_PAIRED_PRINTERS",
                            if (result["ok"] == false)
                              "message":
                                  result["message"] ??
                                  result["error"] ??
                                  "Printer scan failed",
                            if (result["ok"] == false)
                              "error":
                                  result["error"] ??
                                  result["message"] ??
                                  "Printer scan failed",
                            "status": _normalizePrinterStatus(
                              _safeMap(result["status"]),
                            ),
                          };
                        } catch (e) {
                          final status = await _printerService
                              .getPrinterStatus();
                          return _printerFailureResponse(
                            code: "NO_PAIRED_PRINTERS",
                            message: e.toString(),
                            status: status,
                            type: "SCAN_RESULT",
                          );
                        }
                      }

                      if (type == "CONNECT_PRINTER") {
                        final address = payload["address"] as String?;
                        final printerType =
                            payload["printerType"] as String? ?? "bluetooth";
                        if (address == null || address.trim().isEmpty) {
                          final status = await _printerService
                              .getPrinterStatus();
                          return {
                            "ok": false,
                            "code": "PRINTER_DISCONNECTED",
                            "errorCode": "PRINTER_DISCONNECTED",
                            "message": "Printer address is required",
                            "error": "Printer address is required",
                            "status": _normalizePrinterStatus(status),
                          };
                        }
                        try {
                          final result = await _printerService.connectPrinter(
                            address,
                            printerType,
                          );
                          final status = _safeMap(result["status"]);
                          if (status.isNotEmpty) {
                            unawaited(_emitPrinterStatusToWeb(status));
                          }
                          return _normalizePrinterBridgeResult(result);
                        } catch (e) {
                          final status = await _printerService
                              .getPrinterStatus();
                          unawaited(_emitPrinterStatusToWeb(status));
                          return _printerFailureResponse(
                            code: "PRINTER_DISCONNECTED",
                            message: e.toString(),
                            status: status,
                          );
                        }
                      }

                      if (type == "DISCONNECT_PRINTER") {
                        final result = await _printerService
                            .disconnectPrinter();
                        final status = _safeMap(result["status"]);
                        if (status.isNotEmpty) {
                          unawaited(_emitPrinterStatusToWeb(status));
                        }
                        return {
                          "ok": result["ok"] != false,
                          if (result["ok"] == false)
                            "code": result["code"] ?? "PRINTER_DISCONNECTED",
                          if (result["ok"] == false)
                            "errorCode":
                                result["errorCode"] ??
                                result["code"] ??
                                "PRINTER_DISCONNECTED",
                          if (result["ok"] == false)
                            "message":
                                result["message"] ??
                                result["error"] ??
                                "Printer disconnect failed",
                          if (result["ok"] == false)
                            "error":
                                result["error"] ??
                                result["message"] ??
                                "Printer disconnect failed",
                          "status": _normalizePrinterStatus(status),
                        };
                      }

                      if (type == "GET_PRINTER_STATUS") {
                        try {
                          final status = await _printerService
                              .getPrinterStatus();
                          final fullStatus = _normalizePrinterStatus(status);
                          return <String, dynamic>{
                            "ok": true,
                            "status": fullStatus,
                          };
                        } catch (e) {
                          final status = _normalizePrinterStatus({});
                          return {
                            "ok": false,
                            ...status,
                            "code": "PRINTER_DISCONNECTED",
                            "errorCode": "PRINTER_DISCONNECTED",
                            "message": e.toString(),
                            "error": e.toString(),
                            "status": status,
                          };
                        }
                      }

                      if (type == "TEST_PRINT") {
                        try {
                          final result = await _printerService.testPrint();
                          final status = _safeMap(result["status"]);
                          if (status.isNotEmpty) {
                            unawaited(_emitPrinterStatusToWeb(status));
                          }
                          return {
                            "ok": result["ok"] == true,
                            "type": "PRINT_RESULT",
                            if (result["ok"] != true)
                              "code": result["code"] ?? "PRINTER_DISCONNECTED",
                            if (result["ok"] != true)
                              "errorCode":
                                  result["errorCode"] ??
                                  result["code"] ??
                                  "PRINTER_DISCONNECTED",
                            if (result["ok"] != true)
                              "message":
                                  result["message"] ??
                                  result["error"] ??
                                  "Test print failed",
                            if (result["ok"] != true)
                              "error":
                                  result["error"] ??
                                  result["message"] ??
                                  "Test print failed",
                            "status": _normalizePrinterStatus(status),
                          };
                        } catch (e) {
                          final status = await _printerService
                              .getPrinterStatus();
                          unawaited(_emitPrinterStatusToWeb(status));
                          return _printerFailureResponse(
                            code: "PRINTER_DISCONNECTED",
                            message: e.toString(),
                            status: status,
                            type: "PRINT_RESULT",
                          );
                        }
                      }

                      if (type == "GET_BLUETOOTH_STATUS") {
                        try {
                          final result = await DebugLogService.channel
                              .invokeMethod<dynamic>("getBluetoothStatus");
                          if (result is Map) {
                            final status = await _printerService
                                .getPrinterStatus();
                            return {
                              "ok": true,
                              ...Map<String, dynamic>.from(result),
                              "status": _normalizePrinterStatus(status),
                            };
                          }
                          final status = await _printerService
                              .getPrinterStatus();
                          return {
                            "ok": false,
                            "supported": false,
                            "enabled": false,
                            "hasPermission": false,
                            "code": "BLUETOOTH_PERMISSION_DENIED",
                            "errorCode": "BLUETOOTH_PERMISSION_DENIED",
                            "message": "Bluetooth status is unavailable",
                            "error": "Bluetooth status is unavailable",
                            "status": _normalizePrinterStatus(status),
                          };
                        } catch (e) {
                          final status = await _printerService
                              .getPrinterStatus();
                          return {
                            "ok": false,
                            "code": "BLUETOOTH_PERMISSION_DENIED",
                            "errorCode": "BLUETOOTH_PERMISSION_DENIED",
                            "message": e.toString(),
                            "error": e.toString(),
                            "status": _normalizePrinterStatus(status),
                          };
                        }
                      }

                      if (type == "REQUEST_BLUETOOTH_PERMISSION") {
                        try {
                          final result = await _printerService
                              .requestBluetoothPermissions();
                          final status = _safeMap(result["status"]);
                          if (status.isNotEmpty) {
                            unawaited(_emitPrinterStatusToWeb(status));
                          }
                          return {
                            "ok": result["ok"] == true,
                            if (result["ok"] != true)
                              "code":
                                  result["code"] ??
                                  "BLUETOOTH_PERMISSION_DENIED",
                            if (result["ok"] != true)
                              "errorCode":
                                  result["errorCode"] ??
                                  result["code"] ??
                                  "BLUETOOTH_PERMISSION_DENIED",
                            if (result["ok"] != true)
                              "message":
                                  result["message"] ??
                                  result["error"] ??
                                  "Bluetooth permission was denied",
                            if (result["ok"] != true)
                              "error":
                                  result["error"] ??
                                  result["message"] ??
                                  "Bluetooth permission was denied",
                            "status": _normalizePrinterStatus(status),
                          };
                        } catch (e) {
                          final status = await _printerService
                              .getPrinterStatus();
                          return _printerFailureResponse(
                            code: "BLUETOOTH_PERMISSION_DENIED",
                            message: e.toString(),
                            status: status,
                          );
                        }
                      }

                      if (type == "OPEN_BLUETOOTH_SETTINGS" ||
                          type == "OPEN_APP_SETTINGS" ||
                          type == "OPEN_USB_SETTINGS") {
                        try {
                          final result = await _printerService
                              .openNativeSettings(type.toString());
                          final status = _safeMap(result["status"]);
                          if (status.isNotEmpty) {
                            unawaited(_emitPrinterStatusToWeb(status));
                          }
                          return {
                            "ok": result["ok"] == true,
                            if (result["ok"] != true)
                              "code": result["code"] ?? "PRINTER_DISCONNECTED",
                            if (result["ok"] != true)
                              "errorCode":
                                  result["errorCode"] ??
                                  result["code"] ??
                                  "PRINTER_DISCONNECTED",
                            if (result["ok"] != true)
                              "message":
                                  result["message"] ??
                                  result["error"] ??
                                  "Could not open settings",
                            if (result["ok"] != true)
                              "error":
                                  result["error"] ??
                                  result["message"] ??
                                  "Could not open settings",
                            "status": _normalizePrinterStatus(status),
                          };
                        } catch (e) {
                          final status = await _printerService
                              .getPrinterStatus();
                          return _printerFailureResponse(
                            code: "PRINTER_DISCONNECTED",
                            message: e.toString(),
                            status: status,
                          );
                        }
                      }

                      if (type == "UPDATE_PRINTER_SETTINGS") {
                        try {
                          final result = await _printerService.updateSettings(
                            autoPrintEnabled:
                                payload["autoPrintEnabled"] as bool?,
                            printCopies: payload["printCopies"] is int
                                ? payload["printCopies"] as int
                                : null,
                            restaurantName:
                                payload["restaurantName"] as String?,
                            restaurantAddress:
                                payload["restaurantAddress"] as String?,
                            restaurantPhone:
                                payload["restaurantPhone"] as String?,
                          );
                          final status = _safeMap(result["status"]);
                          if (status.isNotEmpty) {
                            unawaited(_emitPrinterStatusToWeb(status));
                          }
                          return {
                            "ok": result["ok"] != false,
                            if (result["ok"] == false)
                              "code": result["code"] ?? "PRINTER_DISCONNECTED",
                            if (result["ok"] == false)
                              "errorCode":
                                  result["errorCode"] ??
                                  result["code"] ??
                                  "PRINTER_DISCONNECTED",
                            if (result["ok"] == false)
                              "message":
                                  result["message"] ??
                                  result["error"] ??
                                  "Printer settings update failed",
                            if (result["ok"] == false)
                              "error":
                                  result["error"] ??
                                  result["message"] ??
                                  "Printer settings update failed",
                            "status": _normalizePrinterStatus(status),
                          };
                        } catch (e) {
                          final status = await _printerService
                              .getPrinterStatus();
                          unawaited(_emitPrinterStatusToWeb(status));
                          return _printerFailureResponse(
                            code: "PRINTER_DISCONNECTED",
                            message: e.toString(),
                            status: status,
                          );
                        }
                      }

                      if (type == "PREPARE_TAP_TO_PAY") {
                        final terminalBaseUrl = payload["terminalBaseUrl"];
                        final locationId = payload["locationId"];

                        if (terminalBaseUrl is! String ||
                            locationId is! String ||
                            terminalBaseUrl.trim().isEmpty ||
                            locationId.trim().isEmpty) {
                          return {
                            "ok": false,
                            "reason": "MISSING_FIELDS",
                            "message":
                                "terminalBaseUrl and locationId are required.",
                          };
                        }

                        await _cacheAndPrewarmTapToPayConfig(
                          terminalBaseUrl,
                          locationId,
                        );

                        return {
                          "ok": true,
                          "type": "PREPARE_TAP_TO_PAY",
                          "status": "STARTED",
                        };
                      }

                      // Tap-to-Pay entrypoint
                      if (type == "START_TAP_TO_PAY") {
                        if (_isPaymentProcessing) {
                          final busyPayload = _buildFallbackPayload(
                            code: "PAYMENT_ALREADY_IN_PROGRESS",
                            reason: "BUSY",
                            message: "A payment is already in progress.",
                          );
                          await _notifyWebStatus(busyPayload);
                          return busyPayload;
                        }

                        final amount = payload["amount"];
                        final currency = payload["currency"];
                        final orderId = payload["orderId"];
                        final paymentIntentId = payload["paymentIntentId"];
                        final clientSecret = payload["clientSecret"];
                        final terminalBaseUrl = payload["terminalBaseUrl"];
                        final locationId = payload["locationId"];

                        final missing = <String, bool>{
                          "amount": amount == null,
                          "currency": currency == null,
                          "orderId": orderId == null,
                          "paymentIntentId": paymentIntentId == null,
                          "clientSecret": clientSecret == null,
                          "terminalBaseUrl": terminalBaseUrl == null,
                          "locationId": locationId == null,
                        };

                        final hasMissing = missing.values.any((v) => v == true);
                        if (hasMissing) {
                          await _showPaymentErrorDialog(
                            title: "Payment Error",
                            message:
                                "Payment request is missing required fields.",
                          );
                          return {
                            "ok": false,
                            "reason": "MISSING_FIELDS",
                            "missing": missing,
                            "hint":
                                "terminalBaseUrl must be LAN IP like http://192.168.1.161:4242 (not localhost).",
                          };
                        }

                        await _cacheAndPrewarmTapToPayConfig(
                          terminalBaseUrl as String,
                          locationId as String,
                        );

                        if (!AppConfig.isNfcEnabled) {
                          final errorPayload = _buildFallbackPayload(
                            code: "NFC_DISABLED_FOR_MODE",
                            reason: "NFC_DISABLED_FOR_MODE",
                            message: "Tap to Pay is not enabled for this mode.",
                          );
                          await _notifyWebStatus(errorPayload);
                          return errorPayload;
                        }

                        final nfcStatus = await _getNfcStatus();
                        final nfcSupported = nfcStatus["supported"] == true;
                        final nfcEnabled = nfcStatus["enabled"] == true;
                        if (!nfcSupported) {
                          final errorPayload = _buildFallbackPayload(
                            code: "NFC_UNSUPPORTED",
                            reason: "NFC_UNSUPPORTED",
                            message:
                                "This device does not support NFC Tap to Pay.",
                          );
                          await _notifyWebStatus(errorPayload);
                          return errorPayload;
                        }
                        if (!nfcEnabled) {
                          await _showNfcDisabledDialog();
                          final errorPayload = _buildFallbackPayload(
                            code: "NFC_DISABLED",
                            reason: "NFC_DISABLED",
                            message:
                                "NFC is disabled. Enable NFC to use Tap to Pay.",
                          );
                          await _notifyWebStatus(errorPayload);
                          return errorPayload;
                        }

                        // Check Location services BEFORE starting payment
                        final locationOk =
                            await _checkLocationServicesEnabled();
                        if (!locationOk) {
                          final errorPayload = _buildFallbackPayload(
                            code: "LOCATION_SERVICES_DISABLED",
                            reason: "LOCATION_SERVICES_DISABLED",
                            message:
                                "Location services disabled. Enable Location to use Tap to Pay.",
                          );
                          await _notifyWebStatus(errorPayload);
                          return errorPayload;
                        }

                        // Check Developer Options BEFORE starting payment
                        final devOptsOk = await _checkDeveloperOptions();
                        if (!devOptsOk) {
                          final errorPayload = _buildFallbackPayload(
                            code: "DEVELOPER_OPTIONS_ENABLED",
                            reason: "DEVELOPER_OPTIONS_ENABLED",
                            message:
                                "Developer Options must be disabled to use Tap to Pay.",
                          );
                          await _notifyWebStatus(errorPayload);
                          return errorPayload;
                        }

                        // Optional prepare dialog for first payment.
                        // By default we skip this popup and start payment
                        // immediately; native startTapToPay will discover/connect.
                        if (!_readerConnected &&
                            AppConfig.showTapToPayPrepareDialog) {
                          if (mounted) {
                            setState(() {
                              _isPaymentProcessing = false;
                            });
                          }
                          await _showTtpPrepareDialog(
                            terminalBaseUrl: terminalBaseUrl,
                            locationId: locationId,
                            isSimulated: AppConfig.isTapToPaySimulated,
                          );
                        }
                        if (mounted) {
                          setState(() {
                            _isPaymentProcessing = true;
                          });
                        }

                        // Optional tap-to-pay instruction popup before NFC screen.
                        if (AppConfig.showTapToPayInstructionPopup) {
                          await _showTapToPayInstruction(
                            amountCents: amount is int
                                ? amount
                                : int.tryParse(amount.toString()) ?? 0,
                            currency: currency?.toString() ?? 'gbp',
                          );
                        }

                        _debugService.log('💳 Starting Tap to Pay payment...');
                        try {
                          final nativeRes = await DebugLogService.channel
                              .invokeMethod("startTapToPay", {
                                "amount": amount,
                                "currency": currency,
                                "orderId": orderId,
                                "paymentIntentId": paymentIntentId,
                                "clientSecret": clientSecret,
                                "terminalBaseUrl": terminalBaseUrl,
                                "locationId": locationId,
                                "isSimulated": AppConfig.isTapToPaySimulated,
                              })
                              .timeout(_tapToPayTimeout);

                          // Reader is confirmed connected — skip prepare dialog on next payment
                          _readerConnected = true;
                          _debugService.log('✅ Payment completed successfully');

                          await _notifyWebStatus({
                            "ok": true,
                            "type": "PAYMENT_RESULT",
                            "data": nativeRes,
                          });

                          final resMap = nativeRes is Map
                              ? Map<String, dynamic>.from(nativeRes)
                              : <String, dynamic>{};
                          if (mounted) {
                            unawaited(
                              _showPaymentSuccessDialog(
                                last4: resMap['last4'] as String?,
                                cardBrand: resMap['cardBrand'] as String?,
                                amountCents: resMap['amount'] as int?,
                                currency: resMap['currency'] as String?,
                              ).catchError((Object error) {
                                _debugService.log(
                                  '⚠️ Failed to show payment success dialog: $error',
                                );
                                return null;
                              }),
                            );
                          }

                          return {"ok": true, "data": nativeRes};
                        } on TimeoutException {
                          await _showPaymentTimeoutDialog();
                          final timeoutPayload = _buildFallbackPayload(
                            code: "PAYMENT_TIMEOUT",
                            reason: "TIMEOUT",
                            message:
                                "Tap to Pay timed out. Continue with card flow.",
                          );
                          await _notifyWebStatus(timeoutPayload);
                          return timeoutPayload;
                        } on PlatformException catch (e) {
                          final normalizedCode = _normalizeCode(e.code);

                          // Connection-related errors mean reader may have disconnected
                          const readerLostCodes = {
                            "CONNECT_FAILED",
                            "NO_READER_FOUND",
                            "DISCOVERY_TIMEOUT",
                            "DISCOVERY_FAILED",
                            "READER_ERROR",
                          };
                          if (readerLostCodes.contains(normalizedCode)) {
                            _readerConnected = false;
                          }

                          // Device capability errors: silently redirect to card flow
                          // (no dialog shown — same behavior as old APK)
                          const silentFallbackCodes = {
                            "NFC_UNSUPPORTED",
                            "NFC_DISABLED",
                            "UNSUPPORTED_OS",
                            "UNSUPPORTED_DEVICE",
                            // Covers: no TEE, outdated security patch, rooted device,
                            // developer options on, hardware keystore missing
                            "TAP_TO_PAY_INSECURE_ENVIRONMENT",
                            "DEVELOPER_OPTIONS_ENABLED",
                            "READER_ERROR",
                            "CONTACTLESS_TRANSACTION_FAILED",
                            "LOCATION_SERVICES_DISABLED",
                            "LOCATION_PERMISSION_DENIED",
                            "SERVER_UNREACHABLE",
                            "PAYMENT_CANCELLED",
                            "BUSY",
                            "PAYMENT_TIMEOUT",
                            "NO_READER_FOUND",
                            "DISCOVERY_TIMEOUT",
                            "CONNECT_FAILED",
                          };

                          final isSilentFallback = silentFallbackCodes.contains(
                            normalizedCode,
                          );
                          final isTimeoutError =
                              normalizedCode == "TIMEOUT" ||
                              normalizedCode == "PAYMENT_TIMEOUT";

                          final errorPayload = _buildFallbackPayload(
                            code: normalizedCode.isEmpty
                                ? "NATIVE_ERROR"
                                : normalizedCode,
                            reason: normalizedCode.isEmpty
                                ? "NATIVE_ERROR"
                                : normalizedCode,
                            message:
                                e.message ??
                                "Tap to Pay unavailable. Use card flow.",
                            details: e.details,
                          );

                          // Only show dialog for truly unexpected errors
                          if (isTimeoutError) {
                            await _showPaymentTimeoutDialog();
                          } else if (!isSilentFallback) {
                            await _showPaymentErrorDialog(
                              title: "Payment Failed",
                              message:
                                  e.message ??
                                  "Payment failed. Please try again.",
                            );
                          }

                          await _notifyWebStatus(errorPayload);
                          return errorPayload;
                        } catch (e) {
                          if (mounted) {
                            setState(() => _isPaymentProcessing = false);
                          }
                          await _showPaymentErrorDialog(
                            title: "Payment Failed",
                            message: "Payment failed. ${e.toString()}",
                          );
                          await _notifyWebStatus({
                            "ok": false,
                            "type": "PAYMENT_RESULT",
                            "reason": "NATIVE_ERROR",
                            "message": e.toString(),
                            "canFallbackToCard": true,
                            "fallbackAction": "USE_EXISTING_CARD_FLOW",
                            "exitFlow": true,
                          });
                          return {
                            "ok": false,
                            "reason": "NATIVE_ERROR",
                            "message": e.toString(),
                            "canFallbackToCard": true,
                            "fallbackAction": "USE_EXISTING_CARD_FLOW",
                            "exitFlow": true,
                          };
                        } finally {
                          if (mounted) {
                            setState(() {
                              _isPaymentProcessing = false;
                            });
                          }
                        }
                      }

                      // Unknown command — log silently, don't show dialog
                      // (older web app versions may send commands this APK doesn't support yet)
                      debugPrint(
                        '⚠️ [kioskBridge] Unknown command: ${type ?? 'null'}',
                      );
                      return {
                        "ok": false,
                        "reason": "UNKNOWN_COMMAND",
                        "type": type,
                      };
                    },
                  );
                },
              ),
            ),
            _buildLoadingOverlay(),
            _buildInAppSplash(),
            _buildPageLoadError(),
          ],
        ),
      ),
    );
  }
}
