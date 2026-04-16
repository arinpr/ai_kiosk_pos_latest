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
    try {
      _debugService.log('🔍 Scanning for printers...');
      final result = await _channel.invokeMethod<dynamic>('scanPrinters');
      if (result is Map) {
        final printers = result['printers'];
        if (printers is List) {
          return printers
              .map((p) => Map<String, dynamic>.from(p as Map))
              .toList();
        }
      }
      return [];
    } on PlatformException catch (e) {
      _debugService.log('❌ Scan failed: ${e.message}');
      return [];
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
      return {'ok': false};
    } on PlatformException catch (e) {
      _debugService.log('❌ Connect failed: ${e.message}');
      return {'ok': false, 'error': e.message};
    }
  }

  /// Disconnect from the current printer.
  Future<void> disconnectPrinter() async {
    try {
      await _channel.invokeMethod<dynamic>('disconnectPrinter');
      _debugService.log('🔌 Printer disconnected');
    } on PlatformException catch (e) {
      _debugService.log('⚠️ Disconnect error: ${e.message}');
    }
  }

  /// Get current printer connection status.
  Future<Map<String, dynamic>> getPrinterStatus() async {
    try {
      final result =
          await _channel.invokeMethod<dynamic>('getPrinterStatus');
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      }
      return {'connected': false};
    } on PlatformException {
      return {'connected': false};
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
  Future<bool> printReceipt(Map<String, dynamic> orderData,
      {int? copies}) async {
    try {
      _debugService.log('🖨️ Printing receipt...');
      final args = Map<String, dynamic>.from(orderData);
      if (copies != null) {
        args['copies'] = copies;
      }
      final result =
          await _channel.invokeMethod<dynamic>('printReceipt', args);
      final ok = result is Map && result['ok'] == true;
      if (ok) {
        _debugService.log('✅ Receipt printed');
      }
      return ok;
    } on PlatformException catch (e) {
      _debugService.log('❌ Print receipt failed: ${e.message}');
      return false;
    }
  }

  /// Print a Kitchen Order Ticket (KOT).
  Future<bool> printKot(Map<String, dynamic> orderData) async {
    try {
      _debugService.log('🖨️ Printing KOT...');
      final result =
          await _channel.invokeMethod<dynamic>('printKot', orderData);
      final ok = result is Map && result['ok'] == true;
      if (ok) {
        _debugService.log('✅ KOT printed');
      }
      return ok;
    } on PlatformException catch (e) {
      _debugService.log('❌ Print KOT failed: ${e.message}');
      return false;
    }
  }

  /// Print a test page.
  Future<bool> testPrint() async {
    try {
      _debugService.log('🖨️ Printing test page...');
      final result = await _channel.invokeMethod<dynamic>('testPrint');
      final ok = result is Map && result['ok'] == true;
      if (ok) {
        _debugService.log('✅ Test page printed');
      }
      return ok;
    } on PlatformException catch (e) {
      _debugService.log('❌ Test print failed: ${e.message}');
      return false;
    }
  }

  /// Print raw ESC/POS bytes (base64-encoded).
  /// Used when the web app controls receipt formatting via PRINT_RAW.
  Future<bool> printRaw(String base64Data, {int copies = 1}) async {
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
      return ok;
    } on PlatformException catch (e) {
      _debugService.log('❌ Raw print failed: ${e.message}');
      return false;
    }
  }

  // ═════════════════════════════════════════════════════════
  // Settings
  // ═════════════════════════════════════════════════════════

  /// Update printer settings.
  Future<void> updateSettings({
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
      await _channel.invokeMethod<dynamic>('updatePrinterSettings', args);
      _debugService.log('⚙️ Printer settings updated');
    } on PlatformException catch (e) {
      _debugService.log('⚠️ Settings update failed: ${e.message}');
    }
  }
}
