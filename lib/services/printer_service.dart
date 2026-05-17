import 'dart:async';
import 'package:flutter/services.dart';
import 'debug_log_service.dart';

/// Singleton service for managing thermal printer operations.
///
/// Wraps MethodChannel calls to the Android native PrinterManager.
/// Uses the same channel as the existing Stripe Terminal integration
/// ('kiosk.stripe.terminal').
class PrinterService {
  static final PrinterService _instance = PrinterService._internal();
  factory PrinterService() => _instance;
  PrinterService._internal();

  static const MethodChannel _channel = MethodChannel('kiosk.stripe.terminal');
  final DebugLogService _debugService = DebugLogService();

  // ═════════════════════════════════════════════════════════
  // Printer Discovery
  // ═════════════════════════════════════════════════════════

  /// Scan for available printers (Bluetooth paired + USB connected).
  /// Returns a list of printer info maps with keys:
  ///   name, address, type ('bluetooth'|'usb'), isPrinter, isConnected
  Future<List<Map<String, dynamic>>> scanPrinters() async {
    final result = await scanPrintersDetailed();
    final printers = result['printers'];
    if (printers is List) {
      return printers.map((p) => Map<String, dynamic>.from(p as Map)).toList();
    }
    return [];
  }

  Future<Map<String, dynamic>> scanPrintersDetailed() async {
    try {
      _debugService.log('🔍 Scanning for printers...');
      final result = await _channel.invokeMethod<dynamic>('scanPrinters');
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      }
      return {
        'ok': false,
        'errorCode': 'NO_PAIRED_PRINTERS',
        'message': 'No printers returned from native bridge',
        'printers': [],
        'status': await getPrinterStatus(),
      };
    } on PlatformException catch (e) {
      _debugService.log('❌ Scan failed: ${e.message}');
      return _failure(
        'NO_PAIRED_PRINTERS',
        e.message ?? 'Printer scan failed',
        printers: const <Map<String, dynamic>>[],
      );
    }
  }

  // ═════════════════════════════════════════════════════════
  // Connection
  // ═════════════════════════════════════════════════════════

  /// Connect to a printer by address and type ('bluetooth' or 'usb').
  Future<Map<String, dynamic>> connectPrinter(
    String address,
    String type,
  ) async {
    try {
      _debugService.log('🔗 Connecting to printer ($type: $address)...');
      final result = await _channel.invokeMethod<dynamic>('connectPrinter', {
        'address': address,
        'type': type,
      });
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      }
      return _failure('PRINTER_DISCONNECTED', 'Printer connection failed');
    } on PlatformException catch (e) {
      _debugService.log('❌ Connect failed: ${e.message}');
      return _failure(
        'PRINTER_DISCONNECTED',
        e.message ?? 'Printer connection failed',
      );
    }
  }

  /// Disconnect from the current printer.
  Future<Map<String, dynamic>> disconnectPrinter() async {
    try {
      final result = await _channel.invokeMethod<dynamic>('disconnectPrinter');
      _debugService.log('🔌 Printer disconnected');
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      }
      return {'ok': true, 'status': await getPrinterStatus()};
    } on PlatformException catch (e) {
      _debugService.log('⚠️ Disconnect error: ${e.message}');
      return _failure(
        'PRINTER_DISCONNECTED',
        e.message ?? 'Printer disconnect failed',
      );
    }
  }

  /// Get current printer connection status.
  Future<Map<String, dynamic>> getPrinterStatus() async {
    try {
      final result = await _channel.invokeMethod<dynamic>('getPrinterStatus');
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      }
      return _emptyStatus();
    } on PlatformException {
      return _emptyStatus();
    }
  }

  // ═════════════════════════════════════════════════════════
  // Printing
  // ═════════════════════════════════════════════════════════

  /// Print a customer receipt.
  ///
  /// [orderData] should contain all order fields:
  ///   orderNumber, items, total, subtotal, service, payment,
  ///   name, phone, address, postCode, discount, discountPercentage,
  ///   deliveryCharge, generalNote, dateTime,
  ///   restaurantName, restaurantAddress, restaurantPhone
  ///
  /// [copies] defaults to the configured print copies setting.
  Future<Map<String, dynamic>> printReceipt(
    Map<String, dynamic> orderData, {
    int? copies,
  }) async {
    try {
      _debugService.log('🖨️ Printing receipt...');
      final args = Map<String, dynamic>.from(orderData);
      if (copies != null) {
        args['copies'] = copies;
      }
      final result = await _channel.invokeMethod<dynamic>('printReceipt', args);
      final ok = result is Map && result['ok'] == true;
      if (ok) {
        _debugService.log('✅ Receipt printed');
      }
      if (result is Map) return Map<String, dynamic>.from(result);
      return _failure('PRINTER_DISCONNECTED', 'Receipt print failed');
    } on PlatformException catch (e) {
      _debugService.log('❌ Print receipt failed: ${e.message}');
      return _failure(
        'PRINTER_DISCONNECTED',
        e.message ?? 'Receipt print failed',
      );
    }
  }

  /// Print a Kitchen Order Ticket (KOT).
  Future<Map<String, dynamic>> printKot(Map<String, dynamic> orderData) async {
    try {
      _debugService.log('🖨️ Printing KOT...');
      final result = await _channel.invokeMethod<dynamic>(
        'printKot',
        orderData,
      );
      final ok = result is Map && result['ok'] == true;
      if (ok) {
        _debugService.log('✅ KOT printed');
      }
      if (result is Map) return Map<String, dynamic>.from(result);
      return _failure('PRINTER_DISCONNECTED', 'KOT print failed');
    } on PlatformException catch (e) {
      _debugService.log('❌ Print KOT failed: ${e.message}');
      return _failure('PRINTER_DISCONNECTED', e.message ?? 'KOT print failed');
    }
  }

  /// Print a test page.
  Future<Map<String, dynamic>> testPrint() async {
    try {
      _debugService.log('🖨️ Printing test page...');
      final result = await _channel.invokeMethod<dynamic>('testPrint');
      final ok = result is Map && result['ok'] == true;
      if (ok) {
        _debugService.log('✅ Test page printed');
      }
      if (result is Map) return Map<String, dynamic>.from(result);
      return _failure('PRINTER_DISCONNECTED', 'Test print failed');
    } on PlatformException catch (e) {
      _debugService.log('❌ Test print failed: ${e.message}');
      return _failure('PRINTER_DISCONNECTED', e.message ?? 'Test print failed');
    }
  }

  /// Print raw ESC/POS bytes (base64-encoded).
  /// Used when the web app controls receipt formatting via PRINT_RAW.
  Future<Map<String, dynamic>> printRaw(
    String base64Data, {
    int copies = 1,
  }) async {
    try {
      _debugService.log('🖨️ Printing raw data...');
      final result = await _channel.invokeMethod<dynamic>('printRaw', {
        'data': base64Data,
        'copies': copies,
      });
      final ok = result is Map && result['ok'] == true;
      if (ok) {
        _debugService.log('✅ Raw print complete');
      }
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      }
      return _failure('PRINTER_DISCONNECTED', 'Raw print failed');
    } on PlatformException catch (e) {
      _debugService.log('❌ Raw print failed: ${e.message}');
      return _failure('PRINTER_DISCONNECTED', e.message ?? 'Raw print failed');
    }
  }

  // ═════════════════════════════════════════════════════════
  // Settings
  // ═════════════════════════════════════════════════════════

  /// Update printer settings.
  Future<Map<String, dynamic>> updateSettings({
    bool? autoPrintEnabled,
    int? printCopies,
    String? restaurantName,
    String? restaurantAddress,
    String? restaurantPhone,
  }) async {
    try {
      final args = <String, dynamic>{};
      if (autoPrintEnabled != null) {
        args['autoPrintEnabled'] = autoPrintEnabled;
      }
      if (printCopies != null) args['printCopies'] = printCopies;
      if (restaurantName != null) args['restaurantName'] = restaurantName;
      if (restaurantAddress != null) {
        args['restaurantAddress'] = restaurantAddress;
      }
      if (restaurantPhone != null) args['restaurantPhone'] = restaurantPhone;
      final result = await _channel.invokeMethod<dynamic>(
        'updatePrinterSettings',
        args,
      );
      _debugService.log('⚙️ Printer settings updated');
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      }
      return {'ok': true, 'status': await getPrinterStatus()};
    } on PlatformException catch (e) {
      _debugService.log('⚠️ Settings update failed: ${e.message}');
      return _failure(
        'PRINTER_DISCONNECTED',
        e.message ?? 'Printer settings update failed',
      );
    }
  }

  Future<Map<String, dynamic>> openNativeSettings(String action) async {
    final method = switch (action) {
      'OPEN_BLUETOOTH_SETTINGS' => 'openBluetoothSettings',
      'OPEN_APP_SETTINGS' => 'openAppSettings',
      'OPEN_USB_SETTINGS' => 'openUsbSettings',
      _ => '',
    };
    if (method.isEmpty) {
      return _failure('PRINTER_DISCONNECTED', 'Unknown settings action');
    }
    try {
      await _channel.invokeMethod<dynamic>(method);
      return {'ok': true, 'status': await getPrinterStatus()};
    } on PlatformException catch (e) {
      return _failure(
        'PRINTER_DISCONNECTED',
        e.message ?? 'Could not open settings',
      );
    }
  }

  Future<Map<String, dynamic>> _failure(
    String code,
    String message, {
    List<Map<String, dynamic>>? printers,
  }) async {
    return {
      'ok': false,
      'errorCode': code,
      'code': code,
      'message': message,
      'error': message,
      if (printers != null) 'printers': printers,
      'status': await getPrinterStatus(),
    };
  }

  Map<String, dynamic> _emptyStatus() {
    return {
      'connected': false,
      'name': '',
      'address': '',
      'type': '',
      'autoPrintEnabled': true,
      'printCopies': 1,
      'lastPrinterName': '',
      'lastPrinterAddress': '',
      'lastPrinterType': '',
      'bluetoothEnabled': false,
      'bluetoothPermissionGranted': false,
      'locationPermissionGranted': false,
      'usbPermissionGranted': true,
    };
  }
}
