package com.example.flutter_camera_bridge.dslr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.example.flutter_camera_bridge.dslr.ptp.PtpCodes
import com.example.flutter_camera_bridge.dslr.ptp.PtpResponseException
import com.example.flutter_camera_bridge.dslr.ptp.PtpSession
import com.example.flutter_camera_bridge.dslr.ptp.UsbPtpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class DslrUsbController(
    private val context: Context,
    private val emit: (Map<String, Any?>) -> Unit
) {
    private companion object {
        const val USB_OPERATION_DELAY_MS = 150L
    }

    private val logTag = "DslrPtp"
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isStarted = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var receiverRegistered = false
    private var connectedDevice: UsbDevice? = null
    private var connectedBrand: String? = null
    private val operationMutex = Mutex()
    private var periodicScanJob: Job? = null
    private var liveSession: PtpSession? = null

    private val permissionIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            3001,
            Intent(permissionAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val nonNullIntent = intent ?: return
            when (nonNullIntent.action) {
                permissionAction -> {
                    val device = nonNullIntent.readUsbDevice()
                    val granted = nonNullIntent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null && granted) {
                        connect(device)
                    } else {
                        // Some OEMs deliver a misleading broadcast (granted=false or missing
                        // UsbDevice extra) even after the user taps Allow. If the runtime
                        // already shows permission granted, connect anyway.
                        val camera = usbManager.deviceList.values.firstOrNull(::isPtpCamera)
                        if (camera != null && usbManager.hasPermission(camera)) {
                            emitDebug(
                                "permission: broadcast said denied/missing device but hasPermission=true; connecting"
                            )
                            connect(camera)
                        } else {
                            emitError("USB permission denied")
                            emitDebug("permission: denied (device=$device granted=$granted)")
                            isConnecting.set(false)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = nonNullIntent.readUsbDevice()
                    if (device != null && isPtpCamera(device)) {
                        emitState("device_attached")
                        emitDebug("usb: attached vendor=0x${device.vendorId.toString(16)} product=0x${device.productId.toString(16)}")
                        ensurePermissionAndConnect(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = nonNullIntent.readUsbDevice()
                    if (device != null && isPtpCamera(device)) {
                        emitState("device_detached")
                        emitDebug("usb: detached vendor=0x${device.vendorId.toString(16)} product=0x${device.productId.toString(16)}")
                        connectedDevice = null
                        connectedBrand = null
                        disconnect()
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    fun start() {
        if (!isStarted.compareAndSet(false, true)) {
            return
        }
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        registerReceiverIfNeeded()
        emitState("starting")
        emitDebug("controller: start()")
        scanAndConnect()
    }

    fun stop() {
        isStarted.set(false)
        periodicScanJob?.cancel()
        periodicScanJob = null
        disconnect()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        scope.cancel()
        isConnecting.set(false)
        emitState("stopped")
        emitDebug("controller: stop()")
    }

    fun isConnected(): Boolean = connectedDevice != null

    fun getConnectedDeviceInfo(): Map<String, Any?>? {
        val device = connectedDevice ?: return null
        return mapOf(
            "vendorId" to device.vendorId,
            "productId" to device.productId,
            "brand" to (connectedBrand ?: brandForVendor(device.vendorId)),
            "deviceName" to (device.productName ?: "USB Camera"),
        )
    }

    suspend fun listImages(limit: Int = 30, offset: Int = 0): List<Map<String, Any?>> {
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        liveSession?.let { session ->
            return session.listImages().drop(safeOffset).take(safeLimit)
        }
        return withTemporarySession { transport ->
            val newestCandidates = listObjectHandleCandidates(transport)
            if (newestCandidates.isEmpty()) {
                emitDebug("gallery: no handles found across all storages")
                return@withTemporarySession emptyList<Map<String, Any?>>()
            }

            val page = newestCandidates.drop(safeOffset).take(safeLimit)
            val results = mutableListOf<Map<String, Any?>>()
            for ((index, pair) in page.withIndex()) {
                pauseBetweenUsbOperations()
                val (_, handle) = pair
                val info = transport.getObjectInfo(handle)
                if (isDisplayableImage(info.fileName, info.objectFormat)) {
                    results.add(
                        mapOf(
                            "handle" to info.handle,
                            "storageId" to info.storageId,
                            "objectFormat" to info.objectFormat,
                            "compressedSize" to info.compressedSize,
                            "fileName" to info.fileName,
                            "captureDate" to info.captureDate
                        )
                    )
                }
                if ((index + 1) % 10 == 0 || index == page.lastIndex) {
                    emitDebug(
                        "gallery: page loaded ${index + 1}/${page.size} " +
                            "(offset=$safeOffset, limit=$safeLimit)"
                    )
                }
            }
            results.sortedWith(
                compareByDescending<Map<String, Any?>> { it["captureDate"] as String? ?: "" }
                    .thenByDescending { (it["handle"] as Int?) ?: -1 }
            )
        }
    }

    suspend fun getThumbnailBytes(handle: Int): ByteArray {
        liveSession?.let { session ->
            return session.getThumbnailBytes(handle)
        }
        return withTemporarySession { transport ->
            transport.getThumbBytes(handle)
        }
    }

    suspend fun getImageBytes(handle: Int): ByteArray {
        liveSession?.let { session ->
            return session.getImageBytes(handle)
        }
        return withTemporarySession { transport ->
            transport.getObjectBytes(handle)
        }
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun scanAndConnect() {
        val devices = usbManager.deviceList.values.toList()
        val camera = devices.firstOrNull(::isPtpCamera)
        if (camera == null) {
            emitState("waiting_for_camera")
            if (devices.isEmpty()) {
                emitDebug(
                    "scan: no PTP camera found (USB host sees 0 devices — connect camera with OTG, " +
                        "or wait a second after plug-in; still looking every 2s)"
                )
            } else {
                val summary = devices.joinToString("; ") { d ->
                    val ifaces = (0 until d.interfaceCount).joinToString(",") { i ->
                        val iface = d.getInterface(i)
                        "cl=0x${iface.interfaceClass.toString(16)} sub=0x${iface.interfaceSubclass.toString(16)}"
                    }
                    "vid=0x${d.vendorId.toString(16)} pid=0x${d.productId.toString(16)} [$ifaces]"
                }
                emitDebug(
                    "scan: no PTP camera found among ${devices.size} device(s). " +
                        "Need interface class 0x6 (still image / PTP). $summary"
                )
            }
            schedulePeriodicScanUntilCameraAppears()
            return
        }
        cancelPeriodicScan()
        emitDebug("scan: found camera vendor=0x${camera.vendorId.toString(16)} product=0x${camera.productId.toString(16)}")
        ensurePermissionAndConnect(camera)
    }

    /** First scan often runs before the kernel finishes enumerating OTG — keep polling until found or stopped. */
    private fun schedulePeriodicScanUntilCameraAppears() {
        if (!isStarted.get() || periodicScanJob?.isActive == true) return
        periodicScanJob = scope.launch {
            while (isActive && isStarted.get() && connectedDevice == null) {
                delay(2000L)
                if (!isStarted.get() || connectedDevice != null) break
                val camera = usbManager.deviceList.values.firstOrNull(::isPtpCamera) ?: continue
                emitDebug("scan: PTP camera appeared (periodic rescan)")
                ensurePermissionAndConnect(camera)
                break
            }
        }
    }

    private fun cancelPeriodicScan() {
        periodicScanJob?.cancel()
        periodicScanJob = null
    }

    private fun ensurePermissionAndConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            emitDebug("permission: already granted")
            connect(device)
        } else {
            usbManager.requestPermission(device, permissionIntent)
            emitState("requesting_permission")
            emitDebug("permission: requesting")
        }
    }

    private fun connect(device: UsbDevice) {
        if (!isStarted.get()) return
        if (!isConnecting.compareAndSet(false, true)) return

        connectedDevice = device
        connectedBrand = brandForVendor(device.vendorId)
        cancelPeriodicScan()
        emitState("connecting")
        emitDebug("connect: ready brand=$connectedBrand vendor=0x${device.vendorId.toString(16)} product=0x${device.productId.toString(16)}")
        startLiveSessionIfNeeded(device)
        emitState("connected")
        emitDebug("connect: camera ready")
        isConnecting.set(false)
    }

    private fun disconnect() {
        isConnecting.set(false)
        stopLiveSession()
        emitDebug("disconnect: session cleared")
    }

    private fun scheduleReconnect() {
        if (!isStarted.get()) return
        scope.launch {
            emitState("reconnecting")
            emitDebug("reconnect: begin")
            repeat(30) {
                if (!isStarted.get() || connectedDevice != null) return@launch
                delay(2000)
                val camera = usbManager.deviceList.values.firstOrNull(::isPtpCamera)
                if (camera != null) {
                    emitDebug("reconnect: camera found, reconnecting")
                    ensurePermissionAndConnect(camera)
                    return@launch
                }
            }
            emitState("waiting_for_camera")
            emitDebug("reconnect: gave up waiting")
        }
    }

    private fun emitState(state: String) {
        emit(
            mapOf(
                "type" to "state",
                "state" to state,
                "brand" to connectedBrand,
                "vendorId" to connectedDevice?.vendorId,
                "productId" to connectedDevice?.productId
            )
        )
    }

    private fun emitError(message: String) {
        emit(mapOf("type" to "error", "message" to message))
        Log.e(logTag, message)
    }

    private fun emitDebug(message: String) {
        emit(mapOf("type" to "debug", "message" to message))
        Log.d(logTag, message)
    }

    private fun emitPhotoDetected(handle: Int) {
        val device = connectedDevice ?: return
        emit(
            mapOf(
                "type" to "photo_detected",
                "handle" to handle,
                "brand" to (connectedBrand ?: brandForVendor(device.vendorId)),
                "vendorId" to device.vendorId,
                "productId" to device.productId
            )
        )
    }

    private fun isPtpCamera(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return true
            }
        }
        return false
    }

    private fun brandForVendor(vendorId: Int): String {
        return when (vendorId) {
            0x04A9 -> "Canon"
            0x04B0 -> "Nikon"
            0x054C -> "Sony"
            else -> "Unknown"
        }
    }

    private fun Intent.readUsbDevice(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun startLiveSessionIfNeeded(device: UsbDevice) {
        stopLiveSession()
        if (device.vendorId != 0x04A9) {
            emitDebug("connect: using short-lived sessions for this camera")
            return
        }

        emitDebug("connect: starting Canon EOS live session")
        val transport = UsbPtpTransport(usbManager, device) { msg ->
            emitDebug(msg)
        }
        val session = PtpSession(
            transport = transport,
            usesCanonEosEvents = true,
            onPhotoDetected = { handle ->
                emitPhotoDetected(handle)
            },
            onDebug = { message ->
                emitDebug(message)
            },
            onError = { error ->
                emitDebug("live-session error: ${error.message ?: error.javaClass.simpleName}")
            }
        )
        liveSession = session
        session.start()
    }

    private fun stopLiveSession() {
        liveSession?.stop()
        liveSession = null
    }

    private suspend fun <T> withTemporarySession(block: suspend (UsbPtpTransport) -> T): T {
        val device = connectedDevice ?: error("Camera not connected")
        if (!usbManager.hasPermission(device)) {
            error("USB permission is not granted for the camera")
        }

        return operationMutex.withLock {
            emitDebug("temp-session: open")
            val transport = UsbPtpTransport(usbManager, device) { msg ->
                emitDebug(msg)
            }
            try {
                transport.open()
                pauseBetweenUsbOperations()
                transport.openSession(1)
                pauseBetweenUsbOperations()
                if (device.vendorId == 0x04A9) {
                    emitDebug("canon: configuring EOS mode")
                    transport.configureCanonEosMode()
                    pauseBetweenUsbOperations()
                } else if (device.vendorId == 0x054C) {
                    emitDebug("sony: configuring SDIO mode")
                    transport.configureSonyMode()
                    pauseBetweenUsbOperations()
                }
                block(transport)
            } finally {
                runCatching { transport.closeSession() }
                pauseBetweenUsbOperations()
                runCatching { transport.close() }
                emitDebug("temp-session: closed")
            }
        }
    }

    private suspend fun listObjectHandleCandidates(
        transport: UsbPtpTransport
    ): List<Pair<Int, Int>> {
        val storageIds = transport.getStorageIds()
        val newestCandidates = mutableListOf<Pair<Int, Int>>()
        for (storageId in storageIds) {
            pauseBetweenUsbOperations()
            val handles = try {
                transport.getObjectHandlesForStorage(storageId)
            } catch (error: Throwable) {
                if (error.isStoreNotAvailable()) {
                    emitDebug(
                        "gallery: storage=0x${storageId.toString(16)} unavailable; skipping"
                    )
                    emptyList()
                } else {
                    throw error
                }
            }
            emitDebug("gallery: storage=0x${storageId.toString(16)} handles=${handles.size}")
            // Most cameras report handles in capture order (oldest -> newest).
            handles.asReversed().forEach { handle ->
                newestCandidates.add(storageId to handle)
            }
        }

        if (newestCandidates.isNotEmpty()) {
            return newestCandidates
        }

        emitDebug(
            "gallery: no handles from ${storageIds.size} storage(s); trying all-storage fallback"
        )
        pauseBetweenUsbOperations()
        val fallbackStorageId = 0xFFFFFFFF.toInt()
        val fallbackHandles = try {
            transport.getObjectHandlesForStorage(fallbackStorageId)
        } catch (error: Throwable) {
            if (error.isStoreNotAvailable()) {
                emitDebug("gallery: all-storage fallback unavailable; returning empty list")
                return emptyList()
            } else {
                throw error
            }
        }
        emitDebug("gallery: all-storage fallback handles=${fallbackHandles.size}")
        fallbackHandles.asReversed().forEach { handle ->
            newestCandidates.add(fallbackStorageId to handle)
        }
        return newestCandidates
    }

    private fun Throwable.isStoreNotAvailable(): Boolean {
        return this is PtpResponseException && responseCode == PtpCodes.RC_StoreNotAvailable
    }

    private suspend fun pauseBetweenUsbOperations() {
        delay(USB_OPERATION_DELAY_MS)
    }

    private fun isDisplayableImage(fileName: String, objectFormat: Int): Boolean {
        if (fileName.isBlank()) return false
        if (objectFormat == 0x3001) return false
        val lowered = fileName.lowercase()
        return lowered.endsWith(".jpg") ||
            lowered.endsWith(".jpeg") ||
            lowered.endsWith(".png") ||
            lowered.endsWith(".cr2") ||
            lowered.endsWith(".cr3") ||
            lowered.endsWith(".arw")
    }
}
