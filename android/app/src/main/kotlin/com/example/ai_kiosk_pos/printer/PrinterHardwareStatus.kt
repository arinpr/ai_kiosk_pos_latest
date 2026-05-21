package com.example.ai_kiosk_pos.printer

internal object PrinterHardwareStatus {
  fun unavailable(reason: String, message: String): Map<String, Any> {
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

  fun fromRealtimeBytes(reason: String, statusBytes: Map<Int, Int>): Map<String, Any> {
    val issues = decodeIssues(statusBytes)
    val issueSet = issues.toSet()
    return mapOf(
      "available" to true,
      "reason" to reason,
      "checkedAt" to System.currentTimeMillis(),
      "raw" to mapOf(
        "printer" to statusBytes[1].toHexStatus(),
        "offline" to statusBytes[2].toHexStatus(),
        "error" to statusBytes[3].toHexStatus(),
        "paper" to statusBytes[4].toHexStatus()
      ),
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

  fun decodeMessage(statusBytes: Map<Int, Int>): String {
    val issues = decodeIssues(statusBytes)
    return if (issues.isEmpty()) "no_error_bits_reported" else issues.joinToString(",")
  }

  private fun decodeIssues(statusBytes: Map<Int, Int>): List<String> {
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

  private fun Int?.toHexStatus(): String {
    return this?.let { String.format("0x%02X", it) } ?: ""
  }
}
