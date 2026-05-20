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
    const val OC_SONYSDIOConnect = 0x9201
    const val OC_SONYSDIOGetExtDeviceInfo = 0x9202

    const val RC_OK = 0x2001
    const val RC_StoreNotAvailable = 0x2013

    const val EC_ObjectAdded = 0x4002
    const val EC_CaptureComplete = 0x400D

    const val EOS_EC_ObjectAdded = 0xC181
    const val EOS_EC_RequestObjectTransfer = 0xC186
    const val EOS_EC_RequestObjectTransferDt = 0xC190
    const val EOS_EC_ObjectAddedEx64 = 0xC1A7
    const val EOS_EC_RequestObjectTransfer64 = 0xC1A9
}
