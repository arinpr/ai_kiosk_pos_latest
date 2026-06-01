import 'dart:async';
import 'package:flutter/services.dart';

/// Centralized service for handling debug logs from native side.
/// This ensures logs are captured regardless of which screen is active.
class DebugLogService {
  static final DebugLogService _instance = DebugLogService._internal();
  factory DebugLogService() => _instance;
  DebugLogService._internal();

  static const MethodChannel _channel = MethodChannel('kiosk.stripe.terminal');

  final List<String> _logs = [];
  final StreamController<String> _logStreamController =
      StreamController<String>.broadcast();
  final StreamController<Map<String, dynamic>> _ttpProgressController =
      StreamController<Map<String, dynamic>>.broadcast();
  final StreamController<Map<String, dynamic>> _printerStatusController =
      StreamController<Map<String, dynamic>>.broadcast();
  final StreamController<bool> _readerDisconnectedController =
      StreamController<bool>.broadcast();
  final StreamController<bool> _readerConnectedController =
      StreamController<bool>.broadcast();

  bool _isInitialized = false;

  /// Stream of debug log messages
  Stream<String> get logStream => _logStreamController.stream;

  /// Stream of TTP progress events
  Stream<Map<String, dynamic>> get ttpProgressStream =>
      _ttpProgressController.stream;

  /// Stream of full printer status snapshots from native.
  Stream<Map<String, dynamic>> get printerStatusStream =>
      _printerStatusController.stream;

  /// Stream of reader disconnected events
  Stream<bool> get readerDisconnectedStream =>
      _readerDisconnectedController.stream;

  /// Stream of reader connected events (from eagerPrepare background connect)
  Stream<bool> get readerConnectedStream => _readerConnectedController.stream;

  /// Get all collected logs
  List<String> get logs => List.unmodifiable(_logs);

  /// Maximum number of logs to keep
  static const int maxLogs = 200;

  /// Initialize the service and set up the method channel handler.
  /// Should be called once at app startup.
  void initialize() {
    if (_isInitialized) return;
    _isInitialized = true;

    _channel.setMethodCallHandler(_handleMethodCall);
    _addLog('🚀 Debug log service initialized');
  }

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onDebugLog':
        final message = call.arguments['message'] as String?;
        if (message != null) {
          _addLog(message);
        }
        break;
      case 'onTtpProgress':
        final args = call.arguments;
        if (args is Map) {
          _ttpProgressController.add(Map<String, dynamic>.from(args));
          // Also log TTP progress for debugging
          final step = args['step'];
          final msg = args['message'];
          _addLog('📊 TTP Progress: step=$step, message=$msg');
        }
        break;
      case 'onReaderDisconnected':
        final args = call.arguments;
        final reason = args is Map ? args['reason'] ?? 'unknown' : 'unknown';
        _addLog('⚠️ Reader disconnected: $reason');
        _readerDisconnectedController.add(true);
        break;
      case 'onPrinterStatusChanged':
        final args = call.arguments;
        if (args is Map) {
          final status = Map<String, dynamic>.from(args);
          _printerStatusController.add(status);
          final connected = status['connected'] == true;
          final name = status['name'] ?? status['lastPrinterName'] ?? '';
          _addLog(
            '🖨️ Printer status: ${connected ? 'connected' : 'disconnected'} ${name.toString()}',
          );
        }
        break;
      case 'onReaderConnected':
        final args = call.arguments;
        final source = args is Map ? args['source'] ?? 'unknown' : 'unknown';
        _addLog('✅ Reader connected ($source)');
        _readerConnectedController.add(true);
        break;
    }
    return null;
  }

  void _addLog(String message) {
    final timestamp = DateTime.now().toString().substring(11, 19);
    final formattedLog = '$timestamp $message';
    _logs.add(formattedLog);

    // Keep only the last maxLogs entries
    if (_logs.length > maxLogs) {
      _logs.removeAt(0);
    }

    // Broadcast to listeners
    _logStreamController.add(formattedLog);
  }

  /// Add a manual log entry (from Flutter code)
  void log(String message) {
    _addLog(message);
  }

  /// Clear all logs
  void clearLogs() {
    _logs.clear();
    _addLog('🗑️ Logs cleared');
  }

  /// Get the method channel for direct native calls
  static MethodChannel get channel => _channel;

  /// Dispose resources
  void dispose() {
    _logStreamController.close();
    _ttpProgressController.close();
    _printerStatusController.close();
    _readerDisconnectedController.close();
    _readerConnectedController.close();
    _channel.setMethodCallHandler(null);
    _isInitialized = false;
  }
}
