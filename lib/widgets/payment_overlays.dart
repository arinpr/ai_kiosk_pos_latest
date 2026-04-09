import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

class KioskDotLoader extends StatefulWidget {
  const KioskDotLoader({super.key, required this.color});

  final Color color;

  @override
  State<KioskDotLoader> createState() => _KioskDotLoaderState();
}

class _KioskDotLoaderState extends State<KioskDotLoader>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 900),
    )..repeat();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: List.generate(3, (index) {
        final start = index * 0.2;
        final end = start + 0.6;
        final animation = CurvedAnimation(
          parent: _controller,
          curve: Interval(start, end, curve: Curves.easeInOut),
        );
        return FadeTransition(
          opacity: animation,
          child: Container(
            width: 7,
            height: 7,
            margin: const EdgeInsets.symmetric(horizontal: 3),
            decoration: BoxDecoration(
              color: widget.color,
              shape: BoxShape.circle,
            ),
          ),
        );
      }),
    );
  }
}

class PaymentSuccessOverlay extends StatefulWidget {
  const PaymentSuccessOverlay({
    super.key,
    required this.amountStr,
    required this.cardStr,
    required this.onDone,
  });

  final String amountStr;
  final String cardStr;
  final VoidCallback onDone;

  @override
  State<PaymentSuccessOverlay> createState() => _PaymentSuccessOverlayState();
}

class _PaymentSuccessOverlayState extends State<PaymentSuccessOverlay>
    with TickerProviderStateMixin {
  static const Duration _autoDismissDuration = Duration(seconds: 8);
  late final AnimationController _checkController;
  late final AnimationController _contentController;
  late final AnimationController _pulseController;
  late final Animation<double> _checkScale;
  late final Animation<double> _checkOpacity;
  late final Animation<double> _strokeProgress;
  late final Animation<double> _contentSlide;
  late final Animation<double> _contentOpacity;
  late final Animation<double> _pulseScale;
  late final Animation<double> _pulseOpacity;
  Timer? _autoDismissTimer;

  @override
  void initState() {
    super.initState();

    _checkController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    );
    _checkScale = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _checkController,
        curve: const Interval(0.0, 0.6, curve: Curves.elasticOut),
      ),
    );
    _checkOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _checkController,
        curve: const Interval(0.0, 0.3, curve: Curves.easeOut),
      ),
    );
    _strokeProgress = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _checkController,
        curve: const Interval(0.3, 1.0, curve: Curves.easeInOut),
      ),
    );

    _contentController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 600),
    );
    _contentSlide = Tween<double>(begin: 30.0, end: 0.0).animate(
      CurvedAnimation(parent: _contentController, curve: Curves.easeOutCubic),
    );
    _contentOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _contentController, curve: Curves.easeOut),
    );

    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    );
    _pulseScale = Tween<double>(
      begin: 1.0,
      end: 1.6,
    ).animate(CurvedAnimation(parent: _pulseController, curve: Curves.easeOut));
    _pulseOpacity = Tween<double>(
      begin: 0.4,
      end: 0.0,
    ).animate(CurvedAnimation(parent: _pulseController, curve: Curves.easeOut));

    _autoDismissTimer = Timer(_autoDismissDuration, () {
      if (mounted) {
        widget.onDone();
      }
    });
    _startAnimations();
  }

  Future<void> _startAnimations() async {
    await Future.delayed(const Duration(milliseconds: 100));
    if (!mounted) return;
    _checkController.forward();
    _pulseController.forward();
    await Future.delayed(const Duration(milliseconds: 400));
    if (!mounted) return;
    _contentController.forward();
  }

  @override
  void dispose() {
    _autoDismissTimer?.cancel();
    _checkController.dispose();
    _contentController.dispose();
    _pulseController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const successGreen = Color(0xFF22C55E);
    const darkGreen = Color(0xFF16A34A);

    return Material(
      color: Colors.transparent,
      child: Center(
        child: Container(
          width: 340,
          padding: const EdgeInsets.fromLTRB(24, 40, 24, 24),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(28),
            boxShadow: [
              BoxShadow(
                color: successGreen.withValues(alpha: 0.25),
                blurRadius: 40,
                spreadRadius: 0,
                offset: const Offset(0, 10),
              ),
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.08),
                blurRadius: 20,
                spreadRadius: 0,
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(
                width: 100,
                height: 100,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    AnimatedBuilder(
                      animation: _pulseController,
                      builder: (context, child) => Transform.scale(
                        scale: _pulseScale.value,
                        child: Opacity(
                          opacity: _pulseOpacity.value,
                          child: Container(
                            width: 80,
                            height: 80,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              border: Border.all(color: successGreen, width: 3),
                            ),
                          ),
                        ),
                      ),
                    ),
                    AnimatedBuilder(
                      animation: _checkController,
                      builder: (context, child) => Transform.scale(
                        scale: _checkScale.value,
                        child: Opacity(
                          opacity: _checkOpacity.value,
                          child: Container(
                            width: 80,
                            height: 80,
                            decoration: const BoxDecoration(
                              shape: BoxShape.circle,
                              gradient: LinearGradient(
                                colors: [successGreen, darkGreen],
                                begin: Alignment.topLeft,
                                end: Alignment.bottomRight,
                              ),
                              boxShadow: [
                                BoxShadow(
                                  color: Color(0x4022C55E),
                                  blurRadius: 20,
                                  spreadRadius: 2,
                                ),
                              ],
                            ),
                            child: CustomPaint(
                              painter: _CheckmarkPainter(
                                progress: _strokeProgress.value,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 24),
              AnimatedBuilder(
                animation: _contentController,
                builder: (context, child) => Transform.translate(
                  offset: Offset(0, _contentSlide.value),
                  child: Opacity(
                    opacity: _contentOpacity.value,
                    child: Column(
                      children: [
                        const Text(
                          'Payment Successful',
                          style: TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.w700,
                            color: Color(0xFF1D1D1F),
                            letterSpacing: -0.3,
                          ),
                        ),
                        const SizedBox(height: 8),
                        if (widget.amountStr.isNotEmpty)
                          Text(
                            widget.amountStr,
                            style: const TextStyle(
                              fontSize: 36,
                              fontWeight: FontWeight.w800,
                              color: darkGreen,
                              letterSpacing: -0.5,
                            ),
                          ),
                        if (widget.cardStr.isNotEmpty) ...[
                          const SizedBox(height: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 12,
                              vertical: 6,
                            ),
                            decoration: BoxDecoration(
                              color: const Color(0xFFF0FDF4),
                              borderRadius: BorderRadius.circular(20),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                const Icon(
                                  Icons.credit_card,
                                  size: 14,
                                  color: Color(0xFF6B7280),
                                ),
                                const SizedBox(width: 6),
                                Text(
                                  widget.cardStr,
                                  style: const TextStyle(
                                    color: Color(0xFF6B7280),
                                    fontSize: 13,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                        const SizedBox(height: 16),
                        const Text(
                          'Payment complete. Please review the amount, then tap Done.',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: Color(0xFF9CA3AF),
                            fontSize: 13,
                            fontWeight: FontWeight.w400,
                          ),
                        ),
                        const SizedBox(height: 6),
                        const Text(
                          'This screen closes automatically in 8 seconds.',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: Color(0xFF9CA3AF),
                            fontSize: 12,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                        const SizedBox(height: 22),
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton(
                            onPressed: widget.onDone,
                            style: FilledButton.styleFrom(
                              backgroundColor: darkGreen,
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(vertical: 16),
                              textStyle: const TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.w800,
                                letterSpacing: 0.2,
                              ),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(18),
                              ),
                            ),
                            child: const Text('Done'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CheckmarkPainter extends CustomPainter {
  _CheckmarkPainter({required this.progress});

  final double progress;

  @override
  void paint(Canvas canvas, Size size) {
    if (progress <= 0) return;

    final paint = Paint()
      ..color = Colors.white
      ..strokeWidth = 4.0
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;

    final path = Path();
    final cx = size.width / 2;
    final cy = size.height / 2;
    final startX = cx - 14;
    final startY = cy + 2;
    final midX = cx - 4;
    final midY = cy + 12;
    final endX = cx + 16;
    final endY = cy - 10;

    path.moveTo(startX, startY);

    if (progress <= 0.5) {
      final t = progress / 0.5;
      path.lineTo(startX + (midX - startX) * t, startY + (midY - startY) * t);
    } else {
      path.lineTo(midX, midY);
      final t = (progress - 0.5) / 0.5;
      path.lineTo(midX + (endX - midX) * t, midY + (endY - midY) * t);
    }

    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(_CheckmarkPainter old) => old.progress != progress;
}

class TapToPayInstructionOverlay extends StatefulWidget {
  const TapToPayInstructionOverlay({
    super.key,
    required this.amountStr,
    required this.onDone,
    this.deviceModel = 'SUNMI Flex 3',
    this.nfcHint = 'Hold Here',
  });

  final String amountStr;
  final VoidCallback onDone;
  final String deviceModel;
  final String nfcHint;

  @override
  State<TapToPayInstructionOverlay> createState() =>
      _TapToPayInstructionOverlayState();
}

class _TapToPayInstructionOverlayState extends State<TapToPayInstructionOverlay>
    with TickerProviderStateMixin {
  static const int _autoDismissSeconds = 8;
  late final AnimationController _enterController;
  late final AnimationController _countdownController;
  late final AnimationController _ambientController;
  bool _dismissed = false;

  @override
  void initState() {
    super.initState();
    _enterController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 420),
    );
    _countdownController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: _autoDismissSeconds),
    );
    _ambientController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2400),
    );
    _countdownController.addStatusListener((status) {
      if (status == AnimationStatus.completed && mounted) {
        _dismissOverlay();
      }
    });
    _enterController.forward();
    _countdownController.forward();
    _ambientController.repeat(reverse: true);
  }

  void _dismissOverlay() {
    if (_dismissed) return;
    _dismissed = true;
    if (_countdownController.isAnimating) {
      _countdownController.stop();
    }
    widget.onDone();
  }

  @override
  void dispose() {
    _enterController.dispose();
    _countdownController.dispose();
    _ambientController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const ringGreen = Color(0xFF22C55E);
    const forestGreen = Color(0xFF166534);
    const deepGreen = Color(0xFF14532D);
    const mint = Color(0xFFF0FDF4);

    return Material(
      color: Colors.transparent,
      child: AnimatedBuilder(
        animation: Listenable.merge([
          _enterController,
          _countdownController,
          _ambientController,
        ]),
        builder: (context, child) {
          final t = Curves.easeOutBack.transform(_enterController.value);
          final countdown = 1.0 - _countdownController.value;
          final pulse = Curves.easeInOutSine.transform(
            _ambientController.value,
          );
          final secondsLeft =
              (_autoDismissSeconds -
                      (_countdownController.value * _autoDismissSeconds))
                  .ceil()
                  .clamp(1, _autoDismissSeconds);
          final iconFloat =
              math.sin(_ambientController.value * math.pi * 2) * 5;

          return Transform.translate(
            offset: Offset(0, (1 - t) * 28),
            child: Opacity(
              opacity: t,
              child: Transform.scale(
                scale: 0.92 + (0.08 * t),
                child: SafeArea(
                  minimum: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 12,
                  ),
                  child: LayoutBuilder(
                    builder: (context, constraints) {
                      final cardWidth = math
                          .min(constraints.maxWidth, 432.0)
                          .toDouble();
                      final heightScale = (constraints.maxHeight / 820)
                          .clamp(0.86, 1.0)
                          .toDouble();
                      final ringSize = math
                          .min(
                            math.min(cardWidth * 0.70, 292.0),
                            constraints.maxHeight * 0.42,
                          )
                          .toDouble();
                      final innerCircleSize = (ringSize * 0.72)
                          .clamp(172.0, 214.0)
                          .toDouble();
                      final titleSize = 28.0 * heightScale;
                      final targetTextSize = 20.0 * heightScale;
                      final amountSize = 28.0 * heightScale;
                      final buttonHeight = (56.0 * heightScale)
                          .clamp(48.0, 56.0)
                          .toDouble();
                      final compactSpacing = 18.0 * heightScale;
                      final bodySpacing = 22.0 * heightScale;

                      return Stack(
                        alignment: Alignment.center,
                        children: [
                          Positioned(
                            top: constraints.maxHeight * 0.08,
                            right: -20,
                            child: Transform.scale(
                              scale: 0.96 + (pulse * 0.10),
                              child: Container(
                                width: 170,
                                height: 170,
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  gradient: RadialGradient(
                                    colors: [
                                      ringGreen.withValues(alpha: 0.20),
                                      ringGreen.withValues(alpha: 0.02),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          ),
                          Positioned(
                            bottom: constraints.maxHeight * 0.10,
                            left: -34,
                            child: Container(
                              width: 190,
                              height: 190,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                gradient: RadialGradient(
                                  colors: [
                                    const Color(
                                      0xFFFDE68A,
                                    ).withValues(alpha: 0.18),
                                    const Color(
                                      0xFFFDE68A,
                                    ).withValues(alpha: 0.02),
                                  ],
                                ),
                              ),
                            ),
                          ),
                          SingleChildScrollView(
                            physics: const ClampingScrollPhysics(),
                            child: Center(
                              child: Container(
                                width: cardWidth,
                                padding: EdgeInsets.fromLTRB(
                                  22,
                                  22 * heightScale,
                                  22,
                                  20 * heightScale,
                                ),
                                decoration: BoxDecoration(
                                  gradient: const LinearGradient(
                                    colors: [
                                      Color(0xFFFFFFFF),
                                      Color(0xFFF8FFF9),
                                      Color(0xFFEFFBF3),
                                    ],
                                    begin: Alignment.topLeft,
                                    end: Alignment.bottomRight,
                                  ),
                                  borderRadius: BorderRadius.circular(30),
                                  border: Border.all(
                                    color: ringGreen.withValues(alpha: 0.12),
                                  ),
                                  boxShadow: [
                                    BoxShadow(
                                      color: ringGreen.withValues(alpha: 0.12),
                                      blurRadius: 42,
                                      spreadRadius: 2,
                                      offset: const Offset(0, 20),
                                    ),
                                    BoxShadow(
                                      color: Colors.black.withValues(
                                        alpha: 0.10,
                                      ),
                                      blurRadius: 18,
                                      offset: const Offset(0, 10),
                                    ),
                                  ],
                                ),
                                child: Stack(
                                  children: [
                                    Positioned(
                                      top: -40,
                                      right: -14,
                                      child: Container(
                                        width: 112,
                                        height: 112,
                                        decoration: BoxDecoration(
                                          shape: BoxShape.circle,
                                          gradient: RadialGradient(
                                            colors: [
                                              ringGreen.withValues(alpha: 0.18),
                                              ringGreen.withValues(alpha: 0.02),
                                            ],
                                          ),
                                        ),
                                      ),
                                    ),
                                    Positioned(
                                      bottom: -54,
                                      left: -26,
                                      child: Container(
                                        width: 132,
                                        height: 132,
                                        decoration: BoxDecoration(
                                          shape: BoxShape.circle,
                                          gradient: RadialGradient(
                                            colors: [
                                              const Color(
                                                0xFFFDE68A,
                                              ).withValues(alpha: 0.14),
                                              const Color(
                                                0xFFFDE68A,
                                              ).withValues(alpha: 0.02),
                                            ],
                                          ),
                                        ),
                                      ),
                                    ),
                                    Column(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        Container(
                                          padding: EdgeInsets.symmetric(
                                            horizontal: 14,
                                            vertical: 8 * heightScale,
                                          ),
                                          decoration: BoxDecoration(
                                            color: deepGreen,
                                            borderRadius: BorderRadius.circular(
                                              999,
                                            ),
                                            boxShadow: [
                                              BoxShadow(
                                                color: deepGreen.withValues(
                                                  alpha: 0.18,
                                                ),
                                                blurRadius: 14,
                                                offset: const Offset(0, 8),
                                              ),
                                            ],
                                          ),
                                          child: const Row(
                                            mainAxisSize: MainAxisSize.min,
                                            children: [
                                              Icon(
                                                Icons.contactless_rounded,
                                                size: 16,
                                                color: Colors.white,
                                              ),
                                              SizedBox(width: 8),
                                              Text(
                                                'Tap to Pay Ready',
                                                style: TextStyle(
                                                  color: Colors.white,
                                                  fontSize: 13,
                                                  fontWeight: FontWeight.w700,
                                                  letterSpacing: 0.2,
                                                ),
                                              ),
                                            ],
                                          ),
                                        ),
                                        SizedBox(height: compactSpacing),
                                        Text(
                                          'Tap on the next screen',
                                          textAlign: TextAlign.center,
                                          style: TextStyle(
                                            fontSize: titleSize,
                                            fontWeight: FontWeight.w900,
                                            color: const Color(0xFF111827),
                                            letterSpacing: -0.8,
                                            height: 1.05,
                                          ),
                                        ),
                                        SizedBox(height: 10 * heightScale),
                                        Text(
                                          'Keep your card or phone ready. The contactless target appears on the next screen.',
                                          textAlign: TextAlign.center,
                                          style: TextStyle(
                                            color: const Color(0xFF4B5563),
                                            fontSize: 14 * heightScale,
                                            fontWeight: FontWeight.w500,
                                            height: 1.45,
                                          ),
                                        ),
                                        SizedBox(height: bodySpacing),
                                        Container(
                                          padding: EdgeInsets.symmetric(
                                            horizontal: 12,
                                            vertical: 10 * heightScale,
                                          ),
                                          decoration: BoxDecoration(
                                            color: Colors.white.withValues(
                                              alpha: 0.66,
                                            ),
                                            borderRadius: BorderRadius.circular(
                                              28,
                                            ),
                                            border: Border.all(
                                              color: ringGreen.withValues(
                                                alpha: 0.08,
                                              ),
                                            ),
                                          ),
                                          child: SizedBox(
                                            width: ringSize,
                                            height: ringSize,
                                            child: Stack(
                                              alignment: Alignment.center,
                                              children: [
                                                Transform.scale(
                                                  scale: 0.94 + (pulse * 0.16),
                                                  child: Container(
                                                    width: ringSize * 0.86,
                                                    height: ringSize * 0.86,
                                                    decoration: BoxDecoration(
                                                      shape: BoxShape.circle,
                                                      gradient: RadialGradient(
                                                        colors: [
                                                          ringGreen.withValues(
                                                            alpha: 0.20,
                                                          ),
                                                          ringGreen.withValues(
                                                            alpha: 0.03,
                                                          ),
                                                        ],
                                                      ),
                                                    ),
                                                  ),
                                                ),
                                                Container(
                                                  width: ringSize * 0.90,
                                                  height: ringSize * 0.90,
                                                  decoration: BoxDecoration(
                                                    shape: BoxShape.circle,
                                                    border: Border.all(
                                                      color: ringGreen
                                                          .withValues(
                                                            alpha:
                                                                0.14 +
                                                                (pulse * 0.16),
                                                          ),
                                                      width: 2,
                                                    ),
                                                  ),
                                                ),
                                                SizedBox.expand(
                                                  child: CircularProgressIndicator(
                                                    value: countdown,
                                                    strokeWidth: 12,
                                                    strokeCap: StrokeCap.round,
                                                    backgroundColor: ringGreen
                                                        .withValues(
                                                          alpha: 0.12,
                                                        ),
                                                    valueColor:
                                                        const AlwaysStoppedAnimation<
                                                          Color
                                                        >(ringGreen),
                                                  ),
                                                ),
                                                Container(
                                                  width: innerCircleSize,
                                                  height: innerCircleSize,
                                                  padding: EdgeInsets.all(
                                                    18 * heightScale,
                                                  ),
                                                  decoration: BoxDecoration(
                                                    shape: BoxShape.circle,
                                                    gradient:
                                                        const LinearGradient(
                                                          colors: [
                                                            Color(0xFFFFFFFF),
                                                            mint,
                                                          ],
                                                          begin:
                                                              Alignment.topLeft,
                                                          end: Alignment
                                                              .bottomRight,
                                                        ),
                                                    border: Border.all(
                                                      color: ringGreen
                                                          .withValues(
                                                            alpha: 0.24,
                                                          ),
                                                    ),
                                                    boxShadow: [
                                                      BoxShadow(
                                                        color: ringGreen
                                                            .withValues(
                                                              alpha: 0.10,
                                                            ),
                                                        blurRadius: 24,
                                                        spreadRadius: 2,
                                                        offset: const Offset(
                                                          0,
                                                          10,
                                                        ),
                                                      ),
                                                    ],
                                                  ),
                                                  child: Column(
                                                    mainAxisAlignment:
                                                        MainAxisAlignment
                                                            .center,
                                                    children: [
                                                      Container(
                                                        padding:
                                                            EdgeInsets.symmetric(
                                                              horizontal: 12,
                                                              vertical:
                                                                  6 *
                                                                  heightScale,
                                                            ),
                                                        decoration: BoxDecoration(
                                                          color: ringGreen
                                                              .withValues(
                                                                alpha: 0.10,
                                                              ),
                                                          borderRadius:
                                                              BorderRadius.circular(
                                                                999,
                                                              ),
                                                        ),
                                                        child: Text(
                                                          widget.nfcHint,
                                                          style: TextStyle(
                                                            color: forestGreen,
                                                            fontSize:
                                                                12 *
                                                                heightScale,
                                                            fontWeight:
                                                                FontWeight.w800,
                                                            letterSpacing: 0.3,
                                                          ),
                                                        ),
                                                      ),
                                                      SizedBox(
                                                        height:
                                                            12 * heightScale,
                                                      ),
                                                      Transform.translate(
                                                        offset: Offset(
                                                          0,
                                                          iconFloat,
                                                        ),
                                                        child: Icon(
                                                          Icons
                                                              .contactless_rounded,
                                                          size:
                                                              62 * heightScale,
                                                          color: forestGreen,
                                                        ),
                                                      ),
                                                      SizedBox(
                                                        height:
                                                            12 * heightScale,
                                                      ),
                                                      Text(
                                                        'Tap card right here\non next page',
                                                        textAlign:
                                                            TextAlign.center,
                                                        style: TextStyle(
                                                          color: deepGreen,
                                                          fontSize:
                                                              targetTextSize,
                                                          fontWeight:
                                                              FontWeight.w800,
                                                          height: 1.2,
                                                          letterSpacing: -0.3,
                                                        ),
                                                      ),
                                                    ],
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        ),
                                        SizedBox(height: bodySpacing),
                                        if (widget.amountStr.isNotEmpty)
                                          Container(
                                            width: double.infinity,
                                            padding: EdgeInsets.symmetric(
                                              horizontal: 18,
                                              vertical: 16 * heightScale,
                                            ),
                                            decoration: BoxDecoration(
                                              color: Colors.white.withValues(
                                                alpha: 0.86,
                                              ),
                                              borderRadius:
                                                  BorderRadius.circular(20),
                                              border: Border.all(
                                                color: const Color(
                                                  0xFFF59E0B,
                                                ).withValues(alpha: 0.22),
                                              ),
                                            ),
                                            child: Row(
                                              children: [
                                                Container(
                                                  width: 42,
                                                  height: 42,
                                                  decoration: BoxDecoration(
                                                    color: const Color(
                                                      0xFFF59E0B,
                                                    ).withValues(alpha: 0.12),
                                                    borderRadius:
                                                        BorderRadius.circular(
                                                          14,
                                                        ),
                                                  ),
                                                  child: const Icon(
                                                    Icons.receipt_long_rounded,
                                                    color: Color(0xFFB45309),
                                                  ),
                                                ),
                                                const SizedBox(width: 14),
                                                Expanded(
                                                  child: Column(
                                                    crossAxisAlignment:
                                                        CrossAxisAlignment
                                                            .start,
                                                    children: [
                                                      Text(
                                                        'Amount due',
                                                        style: TextStyle(
                                                          color: const Color(
                                                            0xFF6B7280,
                                                          ),
                                                          fontSize:
                                                              12 * heightScale,
                                                          fontWeight:
                                                              FontWeight.w700,
                                                        ),
                                                      ),
                                                      const SizedBox(height: 2),
                                                      Text(
                                                        widget.amountStr,
                                                        style: TextStyle(
                                                          fontSize: amountSize,
                                                          fontWeight:
                                                              FontWeight.w900,
                                                          color: const Color(
                                                            0xFF111827,
                                                          ),
                                                          letterSpacing: -0.7,
                                                        ),
                                                      ),
                                                    ],
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        SizedBox(height: 18 * heightScale),
                                        ClipRRect(
                                          borderRadius: BorderRadius.circular(
                                            999,
                                          ),
                                          child: LinearProgressIndicator(
                                            value: countdown,
                                            minHeight: 8,
                                            backgroundColor: ringGreen
                                                .withValues(alpha: 0.10),
                                            valueColor:
                                                const AlwaysStoppedAnimation<
                                                  Color
                                                >(ringGreen),
                                          ),
                                        ),
                                        SizedBox(height: 10 * heightScale),
                                        Text(
                                          'Closing automatically in ${secondsLeft}s',
                                          style: TextStyle(
                                            color: const Color(0xFF6B7280),
                                            fontSize: 13 * heightScale,
                                            fontWeight: FontWeight.w600,
                                          ),
                                        ),
                                        SizedBox(height: 18 * heightScale),
                                        SizedBox(
                                          width: double.infinity,
                                          child: FilledButton(
                                            onPressed: _dismissOverlay,
                                            style: FilledButton.styleFrom(
                                              backgroundColor: deepGreen,
                                              foregroundColor: Colors.white,
                                              minimumSize: Size.fromHeight(
                                                buttonHeight,
                                              ),
                                              shape: RoundedRectangleBorder(
                                                borderRadius:
                                                    BorderRadius.circular(18),
                                              ),
                                              elevation: 0,
                                            ),
                                            child: Text(
                                              'Continue',
                                              style: TextStyle(
                                                fontSize: 17 * heightScale,
                                                fontWeight: FontWeight.w800,
                                              ),
                                            ),
                                          ),
                                        ),
                                      ],
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        ],
                      );
                    },
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
