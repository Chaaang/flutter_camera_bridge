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

data class PtpDeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Int,
    val manufacturer: String,
    val model: String,
    val operations: Set<Int>
) {
    val supportsSonySdio: Boolean
        get() = operations.contains(PtpCodes.OC_SonySdioConnect) ||
            operations.contains(PtpCodes.OC_SonySdioGetExtDeviceInfo) ||
            vendorExtensionId == 0x11

    val summary: String
        get() = "standardVersion=$standardVersion " +
            "vendorExtensionId=0x${vendorExtensionId.toString(16)} " +
            "manufacturer=$manufacturer model=$model ops=${operations.size}"
}

enum class CameraVendorProfile {
    CanonEos,
    SonySdio,
    Standard;

    companion object {
        fun forVendorId(vendorId: Int): CameraVendorProfile {
            return when (vendorId) {
                0x04A9 -> CanonEos
                0x054C -> SonySdio
                else -> Standard
            }
        }
    }
}
