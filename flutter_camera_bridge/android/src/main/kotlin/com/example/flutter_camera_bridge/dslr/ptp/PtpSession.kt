package com.example.flutter_camera_bridge.dslr.ptp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class PtpSession(
    private val transport: UsbPtpTransport,
    private val usesCanonEosEvents: Boolean,
    private val onPhotoDetected: (Int) -> Unit,
    private val onDebug: (String) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private companion object {
        const val USB_OPERATION_DELAY_MS = 150L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val knownHandles = Collections.synchronizedSet(mutableSetOf<Int>())
    private val announcedHandles = Collections.synchronizedSet(mutableSetOf<Int>())
    private val commandQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val objectInfoCache = Collections.synchronizedMap(mutableMapOf<Int, PtpObjectInfo>())

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        scope.launch {
            for (task in commandQueue) {
                if (!running.get()) break
                task()
            }
        }

        scope.launch {
            try {
                transport.open()
                pauseBetweenUsbOperations()
                enqueueCommand("OpenSession") {
                    transport.openSession(1)
                }
                onDebug("session: opened")
                pauseBetweenUsbOperations()
                val deviceInfo = enqueueCommand("GetDeviceInfo") {
                    transport.getDeviceInfoSummary()
                }
                onDebug("device: $deviceInfo")
                pauseBetweenUsbOperations()
                if (usesCanonEosEvents) {
                    enqueueCommand("ConfigureCanonEosMode") {
                        transport.configureCanonEosMode()
                    }
                    pauseBetweenUsbOperations()
                }
                bootstrapKnownHandles()
                eventLoop()
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    fun stop() {
        running.set(false)
        commandQueue.close()
        scope.cancel()
        runCatching { transport.closeSession() }
        runCatching { transport.close() }
        onDebug("session: stopped")
    }

    private suspend fun eventLoop() {
        while (running.get() && currentCoroutineContext().isActive) {
            try {
                if (usesCanonEosEvents) {
                    pollCanonEosEvents()
                } else {
                    pollStandardPtpEvents()
                }
            } catch (t: Throwable) {
                onError(t)
            }
            pauseBetweenUsbOperations()
        }
    }

    private suspend fun pollStandardPtpEvents() {
        val event = transport.readEvent(1000) ?: return
        if (event.type != PtpCodes.CONTAINER_EVENT) return
        onDebug(
            "event: code=0x${event.code.toString(16).padStart(4, '0')} tx=${event.transactionId} params=${
                event.params.joinToString(prefix = "[", postfix = "]") {
                    "0x${it.toString(16).padStart(4, '0')}"
                }
            }"
        )

        when (event.code) {
            PtpCodes.EC_ObjectAdded -> {
                val handle = event.params.firstOrNull() ?: return
                onDebug("event:ObjectAdded handle=0x${handle.toString(16).padStart(4, '0')}")
                announceHandleIfNeeded(handle, "event")
            }
            PtpCodes.EC_CaptureComplete -> {
                onDebug("event:CaptureComplete")
            }
            else -> onDebug("event:Unhandled code=0x${event.code.toString(16).padStart(4, '0')}")
        }
    }

    private suspend fun pollCanonEosEvents() {
        val events = enqueueCommand("EOSGetEvent") {
            transport.pollCanonEosEvents()
        }
        for (event in events) {
            onDebug(
                "canon:event code=0x${event.code.toString(16).padStart(4, '0')} " +
                    "size=${event.sizeBytes} handle=${event.handle ?: "-"}"
            )
            val handle = event.handle ?: continue
            announceHandleIfNeeded(handle, "canon")
        }
    }

    private suspend fun bootstrapKnownHandles() {
        runCatching {
            val storageIds = enqueueCommand("GetStorageIDs[baseline]") {
                transport.getStorageIds()
            }
            val handles = mutableListOf<Int>()
            for (storageId in storageIds) {
                val storageHandles = enqueueCommand("GetObjectHandles[baseline][$storageId]") {
                    transport.getObjectHandlesForStorage(storageId)
                }
                handles.addAll(storageHandles)
            }
            knownHandles.addAll(handles)
            onDebug("handles: baseline=${handles.size}")
        }.onFailure {
            onDebug("handles: baseline failed ${it.message}")
        }
        pauseBetweenUsbOperations()
    }

    private fun announceHandleIfNeeded(handle: Int, source: String) {
        if (!announcedHandles.add(handle)) {
            onDebug("$source: handle already announced 0x${handle.toString(16).padStart(4, '0')}")
            return
        }
        knownHandles.add(handle)
        objectInfoCache.remove(handle)
        onDebug("$source: new handle 0x${handle.toString(16).padStart(4, '0')}")
        onPhotoDetected(handle)
    }

    suspend fun listImages(): List<Map<String, Any?>> {
        val storageIds = enqueueCommand("GetStorageIDs[listImages]") {
            transport.getStorageIds()
        }
        val results = mutableListOf<PtpObjectInfo>()
        for (storageId in storageIds) {
            val handles = enqueueCommand("GetObjectHandles[listImages][$storageId]") {
                transport.getObjectHandlesForStorage(storageId)
            }
            knownHandles.addAll(handles)
            for ((index, handle) in handles.withIndex()) {
                val info = objectInfoCache[handle] ?: enqueueCommand("GetObjectInfo[$handle]") {
                    transport.getObjectInfo(handle)
                }.also { objectInfoCache[handle] = it }

                if (isDisplayableImage(info)) {
                    results.add(info)
                }
                if ((index + 1) % 25 == 0 || index == handles.lastIndex) {
                    onDebug("gallery: storage=0x${storageId.toString(16)} loaded ${index + 1}/${handles.size}")
                }
            }
        }

        return results
            .sortedWith(
                compareByDescending<PtpObjectInfo> { it.captureDate ?: "" }
                    .thenByDescending { it.handle }
            )
            .map { info ->
                mapOf(
                    "handle" to info.handle,
                    "storageId" to info.storageId,
                    "objectFormat" to info.objectFormat,
                    "compressedSize" to info.compressedSize,
                    "fileName" to info.fileName,
                    "captureDate" to info.captureDate
                )
            }
    }

    suspend fun getThumbnailBytes(handle: Int): ByteArray {
        return enqueueCommand("GetThumb[$handle]") {
            transport.getThumbBytes(handle)
        }
    }

    suspend fun getImageBytes(handle: Int): ByteArray {
        return enqueueCommand("GetObjectBytes[$handle]") {
            transport.getObjectBytes(handle)
        }
    }

    private suspend fun pauseBetweenUsbOperations() {
        delay(USB_OPERATION_DELAY_MS)
    }

    private suspend fun <T> enqueueCommand(
        name: String,
        block: suspend () -> T
    ): T {
        val result = CompletableDeferred<T>()
        commandQueue.send {
            try {
                onDebug("queue:start $name")
                val value = block()
                onDebug("queue:done $name")
                result.complete(value)
            } catch (t: Throwable) {
                onDebug("queue:error $name ${t.message}")
                result.completeExceptionally(t)
            }
        }
        return result.await()
    }

    private fun isDisplayableImage(info: PtpObjectInfo): Boolean {
        if (info.fileName.isBlank()) return false
        return when (info.objectFormat) {
            0x3001 -> false
            else -> true
        }
    }
}
