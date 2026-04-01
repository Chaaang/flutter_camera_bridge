package com.example.flutter_camera_bridge.dslr.ptp

data class PtpContainer(
    val length: Int,
    val type: Int,
    val code: Int,
    val transactionId: Int,
    val params: IntArray
)

data class PtpObjectInfo(
    val handle: Int,
    val storageId: Int,
    val objectFormat: Int,
    val compressedSize: Long,
    val fileName: String,
    val captureDate: String?
)

data class CanonEosEvent(
    val code: Int,
    val sizeBytes: Int,
    val handle: Int?
)
