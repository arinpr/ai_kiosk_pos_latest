package com.example.ai_kiosk_pos.printer

import android.util.Log

/**
 * ESC/POS command builder for 80mm thermal printers (48-char width).
 *
 * Generates raw byte arrays using standard ESC/POS commands supported
 * by virtually all 80mm thermal receipt printers (Epson, Star, generic Chinese).
 */
object EscPosCommands {

  // ═══════════════════════════════════════════════════════════
  // Constants
  // ═══════════════════════════════════════════════════════════

  private const val LINE_WIDTH = 48  // 80mm = 48 chars at Font A (match web builder)

  // ═══════════════════════════════════════════════════════════
  // ESC/POS Control Bytes
  // ═══════════════════════════════════════════════════════════

  /** Initialize printer (reset to defaults) */
  val INIT = byteArrayOf(0x1B, 0x40)

  /** Line feed */
  val LF = byteArrayOf(0x0A)

  /** Bold ON — uses both ESC E and ESC ! for Sunmi compatibility */
  val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01, 0x1B, 0x21, 0x08)

  /** Bold OFF — resets both ESC E and ESC ! */
  val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00, 0x1B, 0x21, 0x00)

  /** Double height ON */
  val DOUBLE_HEIGHT_ON = byteArrayOf(0x1B, 0x21, 0x10)

  /** Double width + double height ON */
  val DOUBLE_SIZE_ON = byteArrayOf(0x1B, 0x21, 0x30)

  /** Normal size */
  val NORMAL_SIZE = byteArrayOf(0x1B, 0x21, 0x00)

  /** Align left */
  val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)

  /** Align center */
  val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)

  /** Align right */
  val ALIGN_RIGHT = byteArrayOf(0x1B, 0x61, 0x02)

  /** Underline ON */
  val UNDERLINE_ON = byteArrayOf(0x1B, 0x2D, 0x01)

  /** Underline OFF */
  val UNDERLINE_OFF = byteArrayOf(0x1B, 0x2D, 0x00)

  /** Paper cut (partial) */
  val CUT_PAPER = byteArrayOf(0x1D, 0x56, 0x01)

  /** Paper cut (full) */
  val CUT_PAPER_FULL = byteArrayOf(0x1D, 0x56, 0x00)

  /** White-on-black (inverted) */
  val INVERT_ON = byteArrayOf(0x1D, 0x42, 0x01)
  val INVERT_OFF = byteArrayOf(0x1D, 0x42, 0x00)

  /** Feed N lines before cut (gives space) */
  fun feedLines(n: Int): ByteArray {
    val bytes = mutableListOf<Byte>()
    repeat(n) { bytes.add(0x0A) }
    return bytes.toByteArray()
  }

  /** Open cash drawer (pulse pin 2) */
  val OPEN_CASH_DRAWER = byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0x78.toByte())

  // ═══════════════════════════════════════════════════════════
  // Text Helpers
  // ═══════════════════════════════════════════════════════════

  /**
   * Convert string to bytes for ESC/POS (Code Page 437 / 858 printers).
   *
   * Bug #5 fix: encode as ISO-8859-1 (Latin-1) so all single-byte
   * characters survive exactly. Then swap 0xA3 (Latin-1 £, U+00A3)
   * → 0x9C (CP437 £). No placeholder needed, so a string containing
   * both £ and $ is never corrupted.
   */
  fun text(s: String): ByteArray {
    // ISO-8859-1 maps U+0000–U+00FF 1-to-1, so no index shifting.
    val bytes = s.toByteArray(Charsets.ISO_8859_1)
    for (i in bytes.indices) {
      if (bytes[i] == 0xA3.toByte()) { // Latin-1 £ → CP437 £
        bytes[i] = 0x9C.toByte()
      }
    }
    return bytes
  }

  /** Text with newline */
  fun textLn(s: String): ByteArray = text(s) + LF

  /** Centered text line */
  fun centerText(s: String): ByteArray {
    val trimmed = s.take(LINE_WIDTH)
    val padding = (LINE_WIDTH - trimmed.length) / 2
    return ALIGN_CENTER + textLn(trimmed) + ALIGN_LEFT
  }

  /** Right-aligned text line */
  fun rightText(s: String): ByteArray {
    return ALIGN_RIGHT + textLn(s.take(LINE_WIDTH)) + ALIGN_LEFT
  }

  /** Bold centered text */
  fun boldCenterText(s: String): ByteArray {
    return BOLD_ON + centerText(s) + BOLD_OFF
  }

  /** Double-height centered text (for headers) */
  fun headerText(s: String): ByteArray {
    return DOUBLE_HEIGHT_ON + BOLD_ON + centerText(s) + BOLD_OFF + NORMAL_SIZE
  }

  /** Full-width divider line */
  fun divider(char: Char = '-'): ByteArray {
    return textLn(char.toString().repeat(LINE_WIDTH))
  }

  /** Double-line divider */
  fun doubleDivider(): ByteArray = divider('=')

  fun dashedLine(): ByteArray = textLn("- ".repeat(24).take(LINE_WIDTH))

  fun inverseLine(left: String, right: String = ""): ByteArray {
    val safeRight = right.take(LINE_WIDTH)
    val maxLeft = (LINE_WIDTH - safeRight.length - 1).coerceAtLeast(0)
    val safeLeft = left.take(maxLeft)
    val gap = (LINE_WIDTH - safeLeft.length - safeRight.length).coerceAtLeast(1)
    val line = "$safeLeft${" ".repeat(gap)}$safeRight".padEnd(LINE_WIDTH, ' ').take(LINE_WIDTH)
    return INVERT_ON + textLn(line) + INVERT_OFF
  }

  fun inverseCentered(label: String): ByteArray {
    val trimmed = label.uppercase().take(LINE_WIDTH)
    val pad = ((LINE_WIDTH - trimmed.length) / 2).coerceAtLeast(0)
    return inverseLine("${" ".repeat(pad)}$trimmed")
  }

  /**
   * Two-column line: left-aligned text + right-aligned value.
   * e.g. "Subtotal:                               £19.46"
   */
  fun twoColumn(left: String, right: String): ByteArray {
    val maxLeft = LINE_WIDTH - right.length - 1
    val trimmedLeft = left.take(maxLeft)
    val gap = LINE_WIDTH - trimmedLeft.length - right.length
    val spaces = if (gap > 0) " ".repeat(gap) else " "
    return textLn("$trimmedLeft$spaces$right")
  }

  /**
   * Three-column line for items: qty, name, price.
   * e.g. " 2   Chicken Burger                    £9.98"
   */
  fun itemLine(qty: Int, name: String, price: String): ByteArray {
    val qtyStr = qty.toString().padStart(2)
    val prefix = "$qtyStr   "
    val maxName = LINE_WIDTH - prefix.length - price.length - 1
    val trimmedName = name.take(maxName)
    val gap = LINE_WIDTH - prefix.length - trimmedName.length - price.length
    val spaces = if (gap > 0) " ".repeat(gap) else " "
    return textLn("$prefix$trimmedName$spaces$price")
  }

  /**
   * Indented modifier line.
   * e.g. "       + Extra Cheese (x1)            £1.00"
   */
  fun modifierLine(name: String, qty: Int, price: String): ByteArray {
    val prefix = "  + "
    val qtyStr = if (qty > 1) " x$qty" else ""
    val fullName = "$name$qtyStr"
    val maxName = LINE_WIDTH - prefix.length - price.length - 1
    val trimmedName = fullName.take(maxName)
    val gap = LINE_WIDTH - prefix.length - trimmedName.length - price.length
    val spaces = if (gap > 0) " ".repeat(gap) else " "
    return textLn("$prefix$trimmedName$spaces$price")
  }

  /**
   * Item note line (indented, italic-style).
   * e.g. "       Note: No onions"
   */
  fun noteLine(note: String): ByteArray {
    val prefix = "  Note: "
    val maxNote = LINE_WIDTH - prefix.length
    return textLn("$prefix${note.take(maxNote)}")
  }

  // ═══════════════════════════════════════════════════════════
  // Receipt Builder
  // ═══════════════════════════════════════════════════════════

  /**
   * Build a complete receipt from order data.
   *
   * @param orderData Map containing all order fields:
   *   - orderNumber: String
   *   - service: String (In-Store, Delivery, Collection)
   *   - payment: String (Cash, Card, Terminal)
   *   - name: String (customer name)
   *   - phone: String
   *   - address: String (for delivery)
   *   - postCode: String (for delivery)
   *   - total: Double
   *   - subtotal: Double
   *   - discount: Double
   *   - discountPercentage: Double
   *   - deliveryCharge: Double
   *   - generalNote: String
   *   - dateTime: String
   *   - items: List<Map> with { name, qty, price, total, note, modifiers: List<Map> }
   *   - restaurantName: String
   *   - restaurantAddress: String
   *   - restaurantPhone: String
   */
  fun buildReceipt(orderData: Map<String, Any?>): ByteArray {
    val buffer = mutableListOf<Byte>()

    fun add(bytes: ByteArray) { buffer.addAll(bytes.toList()) }

    // Initialize
    add(INIT)

    // ── Header ──
    val restaurantName = orderData["restaurantName"] as? String ?: "MEGAPOS"
    val restaurantAddress = orderData["restaurantAddress"] as? String
    val restaurantPhone = orderData["restaurantPhone"] as? String

    add(doubleDivider())
    add(headerText(restaurantName))
    if (!restaurantAddress.isNullOrBlank()) {
      add(centerText(restaurantAddress))
    }
    if (!restaurantPhone.isNullOrBlank()) {
      add(centerText("Tel: $restaurantPhone"))
    }
    add(doubleDivider())

    // ── Order Info ──
    val orderNumber = orderData["orderNumber"]?.toString() ?: ""
    val dateTime = orderData["dateTime"] as? String ?: ""
    val service = orderData["service"] as? String ?: ""
    val payment = orderData["payment"] as? String ?: ""
    val customerName = orderData["name"] as? String ?: ""
    val customerPhone = orderData["phone"] as? String ?: ""

    if (orderNumber.isNotBlank()) {
      add(BOLD_ON)
      add(twoColumn("Order #: $orderNumber", dateTime))
      add(BOLD_OFF)
    } else if (dateTime.isNotBlank()) {
      add(textLn(dateTime))
    }

    if (service.isNotBlank()) {
      add(twoColumn("Service: ${capitalize(service)}", "Payment: ${capitalize(payment)}"))
    }

    if (customerName.isNotBlank()) {
      add(twoColumn("Customer: $customerName", ""))
    }
    if (customerPhone.isNotBlank()) {
      add(textLn("Phone: $customerPhone"))
    }

    // Delivery address
    val address = orderData["address"] as? String ?: ""
    val postCode = orderData["postCode"] as? String ?: ""
    if (address.isNotBlank()) {
      add(textLn("Address: $address"))
    }
    if (postCode.isNotBlank()) {
      add(textLn("Postcode: $postCode"))
    }

    add(divider())

    // ── Column Header ──
    // Bug #13: Use twoColumn() so spacing matches the totals rows exactly.
    // "Qty  Item" is 9 chars; align "Price" to the right.
    add(BOLD_ON)
    add(twoColumn("Qty  Item", "Price"))
    add(BOLD_OFF)
    add(divider())

    // ── Line Items ──
    val items = orderData["items"] as? List<*> ?: emptyList<Any>()
    for (rawItem in items) {
      val item = rawItem as? Map<*, *> ?: continue
      val name = item["name"]?.toString() ?: ""
      val nameVariant = item["nameVariant"]?.toString() ?: item["name_variant"]?.toString() ?: ""
      val displayName = if (nameVariant.isNotBlank() && nameVariant != name) "$name ($nameVariant)" else name
      val qty = toInt(item["qty"]) ?: 1
      val total = toDouble(item["total"]) ?: 0.0
      val price = formatMoney(total)

      add(BOLD_ON)
      add(itemLine(qty, displayName, price))
      add(BOLD_OFF)

      // Modifiers
      val modifiers = item["modifiers"] as? List<*> ?: item["modifier"] as? List<*> ?: emptyList<Any>()
      for (rawMod in modifiers) {
        val mod = rawMod as? Map<*, *> ?: continue
        val modName = mod["name"]?.toString() ?: mod["modName"]?.toString() ?: ""
        val modQty = toInt(mod["modqty"]) ?: toInt(mod["modQty"]) ?: 1
        val modPrice = toDouble(mod["price"]) ?: toDouble(mod["modPrice"]) ?: 0.0
        if (modName.isNotBlank()) {
          add(modifierLine(modName, modQty, formatMoney(modPrice * modQty)))
        }
      }

      // Note
      val note = item["note"]?.toString() ?: ""
      if (note.isNotBlank()) {
        add(noteLine(note))
      }
    }

    add(divider())

    // ── Totals ──
    val subtotal = toDouble(orderData["subtotal"]) ?: toDouble(orderData["total"]) ?: 0.0
    val discount = toDouble(orderData["discount"])
    val discountPercentage = toDouble(orderData["discountPercentage"])
    val deliveryCharge = toDouble(orderData["deliveryCharge"])
    val total = toDouble(orderData["total"]) ?: 0.0

    add(twoColumn("Subtotal:", formatMoney(subtotal)))

    if (discount != null && discount > 0) {
      val discLabel = if (discountPercentage != null && discountPercentage > 0) {
        "Discount (${discountPercentage.toInt()}%):"
      } else {
        "Discount:"
      }
      add(twoColumn(discLabel, "-${formatMoney(discount)}"))
    }

    if (deliveryCharge != null && deliveryCharge > 0) {
      add(twoColumn("Delivery:", formatMoney(deliveryCharge)))
    }

    add(BOLD_ON)
    add(DOUBLE_HEIGHT_ON)
    add(twoColumn("TOTAL:", formatMoney(total)))
    add(NORMAL_SIZE)
    add(BOLD_OFF)

    add(doubleDivider())

    // ── General Note ──
    val generalNote = orderData["generalNote"] as? String ?: orderData["genralNote"] as? String ?: ""
    if (generalNote.isNotBlank()) {
      add(textLn("Note: $generalNote"))
      add(divider())
    }

    // ── Footer ──
    add(centerText("Thank you for your order!"))
    add(centerText("Powered by MEGAPOS"))
    add(doubleDivider())

    // Feed + cut
    add(feedLines(4))
    add(CUT_PAPER)

    return buffer.toByteArray()
  }

  /**
   * Build a Kitchen Order Ticket (KOT).
   * Simplified: no prices, just items + modifiers + notes.
   */
  fun buildKot(orderData: Map<String, Any?>): ByteArray {
    val buffer = mutableListOf<Byte>()
    fun add(bytes: ByteArray) { buffer.addAll(bytes.toList()) }

    add(INIT)

    // Header
    add(DOUBLE_SIZE_ON)
    add(BOLD_ON)
    add(centerText("** KOT **"))
    add(NORMAL_SIZE)
    add(BOLD_OFF)
    add(LF)

    val orderNumber = orderData["orderNumber"]?.toString() ?: ""
    val service = orderData["service"] as? String ?: ""
    val dateTime = orderData["dateTime"] as? String ?: ""

    if (orderNumber.isNotBlank()) {
      add(BOLD_ON)
      add(DOUBLE_HEIGHT_ON)
      add(centerText("Order #$orderNumber"))
      add(NORMAL_SIZE)
      add(BOLD_OFF)
    }

    add(twoColumn("Service: ${capitalize(service)}", dateTime))
    add(doubleDivider())

    // Items (no prices)
    val items = orderData["items"] as? List<*> ?: emptyList<Any>()
    for (rawItem in items) {
      val item = rawItem as? Map<*, *> ?: continue
      val name = item["name"]?.toString() ?: ""
      val nameVariant = item["nameVariant"]?.toString() ?: item["name_variant"]?.toString() ?: ""
      val displayName = if (nameVariant.isNotBlank() && nameVariant != name) "$name ($nameVariant)" else name
      val qty = toInt(item["qty"]) ?: 1

      add(BOLD_ON)
      add(DOUBLE_HEIGHT_ON)
      add(textLn("${qty}x $displayName"))
      add(NORMAL_SIZE)
      add(BOLD_OFF)

      // Modifiers
      val modifiers = item["modifiers"] as? List<*> ?: item["modifier"] as? List<*> ?: emptyList<Any>()
      for (rawMod in modifiers) {
        val mod = rawMod as? Map<*, *> ?: continue
        val modName = mod["name"]?.toString() ?: mod["modName"]?.toString() ?: ""
        val modQty = toInt(mod["modqty"]) ?: toInt(mod["modQty"]) ?: 1
        val qtyStr = if (modQty > 1) " x$modQty" else ""
        if (modName.isNotBlank()) {
          add(textLn("   + $modName$qtyStr"))
        }
      }

      // Note
      val note = item["note"]?.toString() ?: ""
      if (note.isNotBlank()) {
        add(BOLD_ON)
        add(textLn("   *** $note ***"))
        add(BOLD_OFF)
      }

      add(LF)
    }

    // General note
    val generalNote = orderData["generalNote"] as? String ?: orderData["genralNote"] as? String ?: ""
    if (generalNote.isNotBlank()) {
      add(doubleDivider())
      add(BOLD_ON)
      add(textLn("NOTE: $generalNote"))
      add(BOLD_OFF)
    }

    add(doubleDivider())
    add(feedLines(4))
    add(CUT_PAPER)

    return buffer.toByteArray()
  }

  /**
   * Compact Chester-style receipt (text ESC/POS only — safe for Bluetooth SPP).
   */
  fun buildChesterReceipt(orderData: Map<String, Any?>): ByteArray {
    val buffer = mutableListOf<Byte>()
    fun add(bytes: ByteArray) { buffer.addAll(bytes.toList()) }

    add(INIT)
    add(LF)

    val restaurantName = (orderData["restaurantName"] as? String)?.trim().orEmpty()
    if (restaurantName.isNotBlank()) {
      add(DOUBLE_SIZE_ON)
      add(BOLD_ON)
      add(centerText(restaurantName.uppercase()))
      add(NORMAL_SIZE)
      add(BOLD_OFF)
    }

    val serviceLabel = normalizeServiceLabel(orderData["service"]?.toString().orEmpty())
    val orderNumber =
      (orderData["displayOrderNumber"] ?: orderData["orderNumber"])?.toString()?.trim().orEmpty()
    add(inverseLine(serviceLabel, if (orderNumber.isNotBlank()) "#$orderNumber" else ""))
    add(dashedLine())
    add(textLn("--"))

    val phone = orderData["phone"]?.toString()?.trim().orEmpty()
    if (phone.isNotBlank()) add(textLn(phone))

    val address = orderData["address"]?.toString()?.trim().orEmpty()
    val postCode = orderData["postCode"]?.toString()?.trim().orEmpty()
    val fullAddress = listOf(address, postCode.takeIf { it.isNotBlank() && !address.contains(it) })
      .filterNotNull()
      .filter { it.isNotBlank() }
      .joinToString(" ")
    if (fullAddress.isNotBlank()) {
      fullAddress.chunked(LINE_WIDTH).forEach { add(textLn(it)) }
    }

    add(divider())

    val items = orderData["items"] as? List<*> ?: emptyList<Any>()
    items.forEachIndexed { index, rawItem ->
      val item = rawItem as? Map<*, *> ?: return@forEachIndexed
      val name = item["name"]?.toString()?.trim().orEmpty().ifBlank { "Unnamed" }
      val variant = item["nameVariant"]?.toString() ?: item["name_variant"]?.toString() ?: ""
      val qty = toInt(item["qty"]) ?: 1
      val total = toDouble(item["total"]) ?: toDouble(item["price"]) ?: 0.0

      add(BOLD_ON)
      add(itemLine(qty, "x ${name.uppercase()}", formatMoney(total)))
      add(BOLD_OFF)

      if (variant.isNotBlank() && variant != name) {
        add(textLn("  + $variant"))
      }

      val modifiers = item["modifiers"] as? List<*> ?: item["modifier"] as? List<*> ?: emptyList<Any>()
      for (rawMod in modifiers) {
        val mod = rawMod as? Map<*, *> ?: continue
        val modName = mod["name"]?.toString() ?: mod["modName"]?.toString() ?: ""
        if (modName.isBlank()) continue
        val modQty = toInt(mod["modqty"]) ?: toInt(mod["modQty"]) ?: 1
        val modPrice = toDouble(mod["price"]) ?: toDouble(mod["modPrice"]) ?: 0.0
        val price = if (modPrice > 0) formatMoney(modPrice * modQty) else ""
        add(modifierLine(modName, modQty, price))
      }

      val note = item["note"]?.toString()?.trim().orEmpty()
      if (note.isNotBlank()) {
        add(BOLD_ON)
        add(textLn("   NOTE: ${note.uppercase()}"))
        add(BOLD_OFF)
      }

      if (index < items.size - 1) add(dashedLine())
    }

    add(divider())

    val deliveryCharge = toDouble(orderData["deliveryCharge"]) ?: 0.0
    val serviceCharge = toDouble(orderData["serviceCharge"]) ?: 0.0
    val discount = toDouble(orderData["discount"]) ?: 0.0
    val total = toDouble(orderData["total"]) ?: 0.0
    val subtotalFromOrder = toDouble(orderData["subtotal"]) ?: 0.0
    val subtotal = if (subtotalFromOrder > 0) {
      subtotalFromOrder
    } else {
      total - deliveryCharge - serviceCharge + discount
    }

    add(twoColumn("Sub Total", formatMoney(subtotal)))
    if (deliveryCharge > 0) add(twoColumn("Delivery", formatMoney(deliveryCharge)))
    if (serviceCharge > 0) add(twoColumn("Service Charge", formatMoney(serviceCharge)))
    if (discount > 0) add(twoColumn("Discount", "-${formatMoney(discount)}"))

    add(BOLD_ON)
    add(DOUBLE_HEIGHT_ON)
    add(twoColumn("Final Total", formatMoney(total)))
    add(NORMAL_SIZE)
    add(BOLD_OFF)

    add(dashedLine())

    val paid = isOrderPaid(orderData)
    add(DOUBLE_SIZE_ON)
    add(BOLD_ON)
    add(centerText(if (paid) "PAID" else "NOT PAID"))
    add(NORMAL_SIZE)
    add(BOLD_OFF)

    add(dashedLine())

    val isReprint = orderData["isReprint"] == true || orderData["is_reprint"] == true
    if (isReprint) {
      val label = (orderData["reprintLabel"] as? String)?.trim().orEmpty().ifBlank { "ORDER REPRINTED" }
      add(inverseCentered(label))
      add(dashedLine())
    }

    val dateTime = orderData["dateTime"]?.toString()?.trim().orEmpty()
    if (dateTime.isNotBlank()) add(textLn(dateTime))
    add(dashedLine())

    val footer = (orderData["generalNote"] as? String)?.trim().orEmpty()
      .ifBlank {
        if (restaurantName.isNotBlank()) "Thank you for ordering at $restaurantName!" else "Thank you for your order!"
      }
    footer.chunked(LINE_WIDTH).forEach { add(textLn(it)) }
    add(dashedLine())

    add(DOUBLE_HEIGHT_ON)
    add(centerText("1"))
    add(NORMAL_SIZE)

    add(feedLines(5))
    add(CUT_PAPER)
    return buffer.toByteArray()
  }

  private fun normalizeServiceLabel(service: String): String {
    val upper = service.uppercase()
    return when {
      upper.contains("DELIVER") -> "DELIVERY"
      upper.contains("COLLECT") -> "COLLECTION"
      upper.contains("IN") || upper.contains("SHOP") || upper.contains("STORE") -> "IN-STORE"
      upper.isBlank() -> "IN-STORE"
      else -> upper
    }
  }

  private fun isOrderPaid(orderData: Map<String, Any?>): Boolean {
    val paidValue = (orderData["isPaid"] ?: orderData["is_paid"] ?: orderData["paid"] ?: "")
      .toString()
      .lowercase()
    val payment = orderData["payment"]?.toString()?.lowercase().orEmpty()
    return paidValue == "1" || paidValue == "true" || paidValue == "paid" || payment == "card"
  }

  /**
   * Build a simple test print page.
   */
  fun buildTestPage(): ByteArray {
    val buffer = mutableListOf<Byte>()
    fun add(bytes: ByteArray) { buffer.addAll(bytes.toList()) }

    add(INIT)
    add(doubleDivider())
    add(headerText("MEGAPOS"))
    add(centerText("Printer Test Page"))
    add(doubleDivider())
    add(textLn("Normal text"))
    add(BOLD_ON + textLn("Bold text") + BOLD_OFF)
    add(DOUBLE_HEIGHT_ON + textLn("Double height") + NORMAL_SIZE)
    add(divider())
    add(twoColumn("Left aligned", "Right"))
    add(itemLine(2, "Test Item", "£9.99"))
    add(modifierLine("Extra Cheese", 1, "£1.50"))
    add(divider())
    add(BOLD_ON + twoColumn("TOTAL:", "£11.49") + BOLD_OFF)
    add(doubleDivider())
    add(centerText("If you can read this,"))
    add(centerText("your printer is working!"))
    add(doubleDivider())
    add(feedLines(4))
    add(CUT_PAPER)

    return buffer.toByteArray()
  }

  // ═══════════════════════════════════════════════════════════
  // Utility Helpers
  // ═══════════════════════════════════════════════════════════

  private fun formatMoney(amount: Double): String {
    // Use the pound sign directly — the printer is configured for
    // Code Page 858 / Windows-1252 which encodes £ correctly.
    return "\u00A3${"%.2f".format(amount)}"
  }

  private fun capitalize(s: String): String {
    return s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
  }

  private fun toDouble(v: Any?): Double? {
    if (v == null) return null
    return when (v) {
      is Double -> v
      is Float -> v.toDouble()
      is Int -> v.toDouble()
      is Long -> v.toDouble()
      is String -> v.toDoubleOrNull()
      else -> v.toString().toDoubleOrNull()
    }
  }

  private fun toInt(v: Any?): Int? {
    if (v == null) return null
    return when (v) {
      is Int -> v
      is Long -> v.toInt()
      is Double -> v.toInt()
      is Float -> v.toInt()
      is String -> v.toIntOrNull()
      else -> v.toString().toIntOrNull()
    }
  }
}
