import 'package:flutter_dotenv/flutter_dotenv.dart';

/// Central configuration class for managing app settings and environment variables
class AppConfig {
  // Private constructor to prevent instantiation
  AppConfig._();

  /// Get the current app mode (test or live)
  static String get appMode =>
      dotenv.env['APP_MODE'] ??
      const String.fromEnvironment('APP_MODE', defaultValue: 'test');

  /// Check if app is running in live mode
  static bool get isLive => appMode.toLowerCase() == 'live';

  /// Fixed kiosk launch mode.
  ///
  /// Values:
  /// - selection (default): show mode selection flow
  /// - kiosk
  /// - large_kiosk
  /// - pos
  /// - mobile_kiosk
  ///
  /// `--dart-define=KIOSK_FIXED_MODE=...` takes precedence over `.env`.
  static String get kioskFixedMode {
    const modeFromDefine = String.fromEnvironment(
      'KIOSK_FIXED_MODE',
      defaultValue: '',
    );
    final raw = modeFromDefine.isNotEmpty
        ? modeFromDefine
        : (dotenv.env['KIOSK_FIXED_MODE'] ?? 'selection');

    final normalized = raw.trim().toLowerCase().replaceAll('-', '_');
    switch (normalized) {
      case 'kiosk':
        return 'kiosk';
      case 'large_kiosk':
      case 'largekiosk':
        return 'large_kiosk';
      case 'pos':
        return 'pos';
      case 'mobile_kiosk':
      case 'mobilekiosk':
        return 'mobile_kiosk';
      case 'selection':
      case 'mode_selection':
      case 'select':
        return 'selection';
      default:
        return 'selection';
    }
  }

  /// Whether to show the mode selection screen.
  static bool get useModeSelection => kioskFixedMode == 'selection';

  /// Title used when fixed mode launch is enabled.
  static String get fixedModeTitle {
    switch (kioskFixedMode) {
      case 'kiosk':
        return 'KIOSK';
      case 'large_kiosk':
        return 'LARGE KIOSK';
      case 'pos':
        return 'POS';
      case 'mobile_kiosk':
        return 'MOBILE KIOSK';
      default:
        return 'KIOSK';
    }
  }

  /// Whether NFC (Tap to Pay) is enabled for the current mode.
  /// Reads ENABLE_NFC_<MODE> from .env. Defaults to true if not set.
  static bool get isNfcEnabled {
    final key = 'ENABLE_NFC_${kioskFixedMode.toUpperCase()}';
    final raw =
        dotenv.env[key] ??
        const String.fromEnvironment('ENABLE_NFC', defaultValue: '');
    if (raw.isEmpty) return true;
    return raw.toLowerCase() == 'true';
  }

  /// Check if tap to pay is simulated (defaults to true in test mode, false in live)
  static bool get isTapToPaySimulated {
    final raw =
        dotenv.env['TAP_TO_PAY_SIMULATED'] ??
        const String.fromEnvironment('TAP_TO_PAY_SIMULATED', defaultValue: '');
    if (raw.isEmpty) return !isLive;
    return raw.toLowerCase() == 'true';
  }

  /// Controls the first-payment prepare popup (Initializing/Connecting/Downloading).
  /// Default false: skip popup and start payment flow immediately.
  static bool get showTapToPayPrepareDialog {
    final raw =
        dotenv.env['SHOW_TTP_PREPARE_DIALOG'] ??
        const String.fromEnvironment(
          'SHOW_TTP_PREPARE_DIALOG',
          defaultValue: 'false',
        );
    return raw.toLowerCase() == 'true';
  }

  /// Controls the Tap-to-Pay instruction popup before NFC collection.
  /// Default true: show popup. Set false to skip it.
  static bool get showTapToPayInstructionPopup {
    final raw =
        dotenv.env['SHOW_TTP_INSTRUCTION_POPUP'] ??
        const String.fromEnvironment(
          'SHOW_TTP_INSTRUCTION_POPUP',
          defaultValue: 'true',
        );
    return raw.toLowerCase() == 'true';
  }

  // ========== TEST URL (Single URL for all modes) ==========

  /// Single test URL for all modes (local development)
  static String get testUrl =>
      dotenv.env['TEST_URL'] ??
      const String.fromEnvironment(
        'TEST_URL',
        defaultValue: 'http://192.168.1.161:3000',
      );

  // ========== KIOSK Mode URLs ==========

  /// Kiosk URL for live environment
  static String get kioskUrlLive =>
      dotenv.env['KIOSK_URL_LIVE'] ??
      const String.fromEnvironment(
        'KIOSK_URL_LIVE',
        defaultValue: 'https://aikiosk.example.com/kiosk',
      );

  /// Get active Kiosk URL based on current mode
  static String get kioskUrl => isLive ? kioskUrlLive : testUrl;

  // ========== LARGE KIOSK Mode URLs ==========

  /// Large Kiosk URL for live environment
  static String get largeKioskUrlLive =>
      dotenv.env['LARGE_KIOSK_URL_LIVE'] ??
      const String.fromEnvironment(
        'LARGE_KIOSK_URL_LIVE',
        defaultValue: 'https://aikiosk.example.com/largekiosk',
      );

  /// Get active Large Kiosk URL based on current mode
  static String get largeKioskUrl => isLive ? largeKioskUrlLive : testUrl;

  // ========== POS Mode URLs ==========

  /// POS URL for live environment
  static String get posUrlLive =>
      dotenv.env['POS_URL_LIVE'] ??
      const String.fromEnvironment(
        'POS_URL_LIVE',
        defaultValue: 'https://aikiosk.example.com/pos',
      );

  /// Get active POS URL based on current mode
  static String get posUrl => isLive ? posUrlLive : testUrl;

  // ========== MOBILE KIOSK Mode URLs ==========

  /// Mobile Kiosk URL for live environment
  static String get mobileKioskUrlLive =>
      dotenv.env['MOBILE_KIOSK_URL_LIVE'] ??
      const String.fromEnvironment(
        'MOBILE_KIOSK_URL_LIVE',
        defaultValue: 'https://aikiosk.example.com/mobilekiosk',
      );

  /// Get active Mobile Kiosk URL based on current mode
  static String get mobileKioskUrl => isLive ? mobileKioskUrlLive : testUrl;

  /// URL used when fixed mode launch is enabled.
  static String get fixedModeUrl {
    switch (kioskFixedMode) {
      case 'kiosk':
        return kioskUrl;
      case 'large_kiosk':
        return largeKioskUrl;
      case 'pos':
        return posUrl;
      case 'mobile_kiosk':
        return mobileKioskUrl;
      default:
        return kioskUrl;
    }
  }
}
