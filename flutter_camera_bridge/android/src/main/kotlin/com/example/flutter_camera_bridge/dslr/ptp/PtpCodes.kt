package com.example.flutter_camera_bridge.dslr.ptp

object PtpCodes {
    const val CONTAINER_COMMAND = 1
    const val CONTAINER_DATA = 2
    const val CONTAINER_RESPONSE = 3
    const val CONTAINER_EVENT = 4

    const val OC_GetDeviceInfo = 0x1001
    const val OC_OpenSession = 0x1002
    const val OC_CloseSession = 0x1003
    const val OC_GetStorageIDs = 0x1004
    const val OC_GetObjectHandles = 0x1007
    const val OC_GetObjectInfo = 0x1008
    const val OC_GetObject = 0x1009
    const val OC_GetThumb = 0x100A
    const val OC_EOSSetRemoteMode = 0x9114
    const val OC_EOSSetEventMode = 0x9115
    const val OC_EOSGetEvent = 0x9116
    const val OC_EOSKeepDeviceOn = 0x911D

    const val OC_SonySdioConnect = 0x9201
    const val OC_SonySdioGetExtDeviceInfo = 0x9202
    const val OC_SonySetControlDeviceA = 0x9205
    const val OC_SonySetContentsTransferMode = 0x9212
    const val DPC_SonyPriorityMode = 0xD25A

    const val RC_OK = 0x2001
    const val RC_GeneralError = 0x2002
    const val RC_SessionNotOpen = 0x2003
    const val RC_OperationNotSupported = 0x2005
    const val RC_DeviceBusy = 0x2019
    const val RC_SessionAlreadyOpen = 0x201E

    fun responseName(code: Int): String {
        val hex = "0x${code.toString(16).padStart(4, '0')}"
        val name = when (code) {
            RC_OK -> "OK"
            RC_GeneralError -> "GeneralError"
            RC_SessionNotOpen -> "SessionNotOpen"
            RC_OperationNotSupported -> "OperationNotSupported"
            RC_DeviceBusy -> "DeviceBusy"
            RC_SessionAlreadyOpen -> "SessionAlreadyOpen"
            0x2013 -> "StoreNotAvailable"
            else -> "Unknown"
        }
        return "$hex ($name)"
    }

    const val EC_ObjectAdded = 0x4002
    const val EC_CaptureComplete = 0x400D

    const val EOS_EC_ObjectAdded = 0xC181
    const val EOS_EC_RequestObjectTransfer = 0xC186
    const val EOS_EC_RequestObjectTransferDt = 0xC190
    const val EOS_EC_ObjectAddedEx64 = 0xC1A7
    const val EOS_EC_RequestObjectTransfer64 = 0xC1A9

    const val EC_SonyObjectAdded = 0xC201
    const val EC_SonyObjectRemoved = 0xC202
    const val EC_SonyPropertyChanged = 0xC203

    const val SONY_EXT_DEVICE_INFO_VERSION = 0xC8

    fun isSonyVirtualObjectHandle(handle: Int): Boolean {
        val unsigned = handle.toLong() and 0xFFFFFFFFL
        return unsigned == 0xFFFFC001L || unsigned == 0xFFFFC002L
    }
}
