package com.example.flutter_camera_bridge.dslr.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class UsbPtpTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val onDebug: ((String) -> Unit)? = null
) {
    private var connection: UsbDeviceConnection? = null
    private var ptpInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var interruptIn: UsbEndpoint? = null
    private val txId = AtomicInteger(1)
    private var bulkInPending = ByteArray(0)

    fun open() {
        debug("open: vendor=0x${device.vendorId.toHex()} product=0x${device.productId.toHex()}")
        val usbInterface = findPtpInterface(device) ?: error("PTP interface not found")
        val usbConnection = usbManager.openDevice(device) ?: error("Unable to open USB device")
        if (!usbConnection.claimInterface(usbInterface, true)) {
            usbConnection.close()
            error("Unable to claim USB interface")
        }

        ptpInterface = usbInterface
        connection = usbConnection
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                endpoint.direction == UsbConstants.USB_DIR_IN
            ) {
                bulkIn = endpoint
            } else if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                endpoint.direction == UsbConstants.USB_DIR_OUT
            ) {
                bulkOut = endpoint
            } else if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                endpoint.direction == UsbConstants.USB_DIR_IN
            ) {
                interruptIn = endpoint
            }
        }

        requireNotNull(bulkIn) { "Bulk IN endpoint missing" }
        requireNotNull(bulkOut) { "Bulk OUT endpoint missing" }
        requireNotNull(interruptIn) { "Interrupt IN endpoint missing" }
        bulkInPending = ByteArray(0)
        debug("open: endpoints ready bulkIn=${bulkIn?.maxPacketSize} bulkOut=${bulkOut?.maxPacketSize} intIn=${interruptIn?.maxPacketSize}")
    }

    fun close() {
        debug("close: releasing USB resources")
        val localConnection = connection
        val localInterface = ptpInterface
        if (localConnection != null && localInterface != null) {
            runCatching { localConnection.releaseInterface(localInterface) }
        }
        runCatching { localConnection?.close() }
        connection = null
        ptpInterface = null
        bulkIn = null
        bulkOut = null
        interruptIn = null
        bulkInPending = ByteArray(0)
    }

    fun nextTxId(): Int = txId.getAndIncrement()

    fun openSession(sessionId: Int = 1) {
        // Working Sony traces use transaction 1. Fall back to 0 if the body rejects it.
        val opened = tryOpenSession(tx = 1, sessionId = sessionId)
        txId.set(2)
        if (opened) return
        require(tryOpenSession(tx = 0, sessionId = sessionId)) { "OpenSession failed" }
    }

    fun getDeviceInfo(): PtpDeviceInfo {
        val tx = nextTxId()
        debug("cmd: GetDeviceInfo tx=$tx")
        sendCommand(PtpCodes.OC_GetDeviceInfo, tx)

        val (dataContainer, payload, response) = readDataAndResponse(5000)
        require(dataContainer.type == PtpCodes.CONTAINER_DATA) {
            "GetDeviceInfo expected DATA container"
        }
        require(response.type == PtpCodes.CONTAINER_RESPONSE) {
            "GetDeviceInfo expected RESPONSE container"
        }
        require(response.code == PtpCodes.RC_OK) {
            "GetDeviceInfo failed with ${PtpCodes.responseName(response.code)}"
        }

        val info = parseDeviceInfo(payload)
        debug("data: GetDeviceInfo tx=$tx ${info.summary}")
        return info
    }

    fun getDeviceInfoSummary(): String = getDeviceInfo().summary

    fun prepareVendorSession(profile: CameraVendorProfile) {
        val info = runCatching { getDeviceInfo() }.getOrElse { error ->
            debug("device: GetDeviceInfo failed ${error.message}")
            if (profile == CameraVendorProfile.CanonEos) {
                configureCanonEosMode()
            }
            return
        }
        debug("device: ${info.summary}")
        when (profile) {
            CameraVendorProfile.CanonEos -> configureCanonEosMode()
            CameraVendorProfile.SonySdio -> {
                if (info.supportsSonySdio) {
                    val configured = configureSonySdioMode()
                    debug("sony: sdio-mode configured=$configured")
                    enableSonyPcControl()
                } else {
                    debug("sony: SDIO opcodes not advertised; using standard PTP")
                }
            }
            CameraVendorProfile.Standard -> Unit
        }
    }

    fun closeSession() {
        runCatching {
            val tx = nextTxId()
            debug("cmd: CloseSession tx=$tx")
            sendCommand(PtpCodes.OC_CloseSession, tx)
            val response = readResponse(3000)
            debug("rsp: CloseSession tx=$tx code=0x${response.code.toHex()} type=${response.type}")
        }
    }

    fun sendCommand(opCode: Int, tx: Int, params: IntArray = intArrayOf()) {
        debug(
            "cmd: code=0x${opCode.toHex()} tx=$tx params=${
                params.joinToString(prefix = "[", postfix = "]") { "0x${it.toHex()}" }
            }"
        )
        val length = 12 + (params.size * 4)
        val container = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        container.putInt(length)
        container.putShort(PtpCodes.CONTAINER_COMMAND.toShort())
        container.putShort(opCode.toShort())
        container.putInt(tx)
        params.forEach { container.putInt(it) }
        writeBulk(container.array(), 3000)
    }

    fun readEvent(timeoutMs: Int = 1000): PtpContainer? {
        val conn = requireNotNull(connection)
        val endpoint = requireNotNull(interruptIn)
        val buffer = ByteArray(512)
        val count = conn.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        if (count <= 0) return null
        val event = parseContainer(buffer.copyOf(count))
        debug(
            "evt: code=0x${event.code.toHex()} tx=${event.transactionId} params=${
                event.params.joinToString(prefix = "[", postfix = "]") { "0x${it.toHex()}" }
            }"
        )
        return event
    }

    fun readResponse(timeoutMs: Int = 3000): PtpContainer {
        val endpoint = requireNotNull(bulkIn)
        val response = readContainer(endpoint, timeoutMs)
        debug(
            "rsp: code=0x${response.code.toHex()} tx=${response.transactionId} params=${
                response.params.joinToString(prefix = "[", postfix = "]") { "0x${it.toHex()}" }
            }"
        )
        return response
    }

    fun getObjectToFile(objectHandle: Int, outFile: File) {
        val tx = nextTxId()
        debug("cmd: GetObject tx=$tx handle=0x${objectHandle.toHex()} out=${outFile.name}")
        sendCommand(PtpCodes.OC_GetObject, tx, intArrayOf(objectHandle))

        val conn = requireNotNull(connection)
        val endpoint = requireNotNull(bulkIn)

        val firstChunk = ByteArray(16 * 1024)
        val firstCount = conn.bulkTransfer(endpoint, firstChunk, firstChunk.size, 10_000)
        require(firstCount >= 12) { "GetObject first transfer failed ($firstCount)" }

        val totalLen = leInt(firstChunk, 0)
        val containerType = leShort(firstChunk, 4).toInt() and 0xFFFF
        debug("data: GetObject tx=$tx totalLen=$totalLen containerType=$containerType")
        require(containerType == PtpCodes.CONTAINER_DATA) {
            "Expected DATA container, got type=$containerType"
        }

        val payloadLen = totalLen - 12
        var remaining = payloadLen

        FileOutputStream(outFile).use { output ->
            val firstPayload = firstCount - 12
            if (firstPayload > 0) {
                output.write(firstChunk, 12, firstPayload)
                remaining -= firstPayload
            }

            val chunk = ByteArray(32 * 1024)
            while (remaining > 0) {
                val request = minOf(chunk.size, remaining)
                val read = conn.bulkTransfer(endpoint, chunk, request, 10_000)
                require(read > 0) {
                    "GetObject interrupted while reading object=$objectHandle remaining=$remaining"
                }
                output.write(chunk, 0, read)
                remaining -= read
            }
            output.flush()
        }

        val response = readResponse(5000)
        debug("rsp: GetObject tx=$tx handle=0x${objectHandle.toHex()} code=0x${response.code.toHex()}")
        require(response.type == PtpCodes.CONTAINER_RESPONSE) {
            "GetObject expected RESPONSE container"
        }
        require(response.code == PtpCodes.RC_OK) {
            "GetObject failed with code 0x${response.code.toString(16)}"
        }
    }

    fun getObjectHandlesAll(): List<Int> {
        return getObjectHandlesForStorage(0xFFFFFFFF.toInt())
    }

    fun getStorageIds(): List<Int> {
        val first = runCatching { getStorageIdsOnce() }
        if (first.isSuccess) return first.getOrThrow()
        val error = first.exceptionOrNull() ?: return emptyList()
        if (device.vendorId != 0x054C || !isSonyStorageRetry(error)) throw error

        debug("sony: GetStorageIDs failed (${error.message}); enabling PC control and retrying")
        runCatching { configureSonySdioMode() }
        runCatching { enableSonyPcControl() }
        return getStorageIdsOnce()
    }

    private fun getStorageIdsOnce(): List<Int> {
        val tx = nextTxId()
        debug("cmd: GetStorageIDs tx=$tx")
        sendCommand(PtpCodes.OC_GetStorageIDs, tx)

        val (payload, response) = readMaybeDataAndResponse(5000)
        require(response.type == PtpCodes.CONTAINER_RESPONSE) {
            "GetStorageIDs expected RESPONSE container"
        }
        require(response.code == PtpCodes.RC_OK) {
            "GetStorageIDs failed with ${PtpCodes.responseName(response.code)}"
        }
        if (payload == null || payload.size < 4) {
            debug("data: GetStorageIDs empty payload")
            return emptyList()
        }

        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.int.coerceAtLeast(0)
        val storageIds = ArrayList<Int>(count)
        repeat(count) {
            if (bb.remaining() >= 4) {
                storageIds.add(bb.int)
            }
        }
        debug("data: GetStorageIDs tx=$tx count=${storageIds.size}")
        return storageIds
    }

    fun getObjectHandlesForStorage(storageId: Int): List<Int> {
        val tx = nextTxId()
        val params = intArrayOf(storageId, 0x00000000, 0x00000000)
        debug("cmd: GetObjectHandles tx=$tx storage=0x${storageId.toHex()}")
        sendCommand(PtpCodes.OC_GetObjectHandles, tx, params)

        val (dataContainer, payload, response) = readDataAndResponse(5000)
        require(dataContainer.type == PtpCodes.CONTAINER_DATA) {
            "GetObjectHandles expected DATA container"
        }
        require(response.type == PtpCodes.CONTAINER_RESPONSE) {
            "GetObjectHandles expected RESPONSE container"
        }
        require(response.code == PtpCodes.RC_OK) {
            "GetObjectHandles failed with code=0x${response.code.toHex()}"
        }

        if (payload.size < 4) {
            debug("data: GetObjectHandles empty payload")
            return emptyList()
        }

        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.int.coerceAtLeast(0)
        val handles = ArrayList<Int>(count)
        repeat(count) {
            if (bb.remaining() >= 4) {
                handles.add(bb.int)
            }
        }
        debug("data: GetObjectHandles tx=$tx storage=0x${storageId.toHex()} count=${handles.size}")
        return handles
    }

    fun getObjectInfo(handle: Int): PtpObjectInfo {
        val tx = nextTxId()
        debug("cmd: GetObjectInfo tx=$tx handle=0x${handle.toHex()}")
        sendCommand(PtpCodes.OC_GetObjectInfo, tx, intArrayOf(handle))

        val (dataContainer, payload, response) = readDataAndResponse(5000)
        require(dataContainer.type == PtpCodes.CONTAINER_DATA) {
            "GetObjectInfo expected DATA container"
        }
        require(response.type == PtpCodes.CONTAINER_RESPONSE) {
            "GetObjectInfo expected RESPONSE container"
        }
        require(response.code == PtpCodes.RC_OK) {
            "GetObjectInfo failed with code=0x${response.code.toHex()}"
        }
        require(payload.size >= 52) { "GetObjectInfo payload too small (${payload.size})" }

        val storageId = leInt(payload, 0)
        val objectFormat = leShort(payload, 4).toInt() and 0xFFFF
        val compressedSize = leInt(payload, 8).toLong() and 0xFFFFFFFFL
        var offset = 52
        val (fileName, afterFileName) = readPtpString(payload, offset)
        offset = afterFileName
        val (captureDate, _) = readPtpString(payload, offset)

        val info = PtpObjectInfo(
            handle = handle,
            storageId = storageId,
            objectFormat = objectFormat,
            compressedSize = compressedSize,
            fileName = fileName,
            captureDate = captureDate.ifBlank { null }
        )
        debug(
            "data: GetObjectInfo tx=$tx handle=0x${handle.toHex()} file=${info.fileName} " +
                "format=0x${info.objectFormat.toHex()} size=${info.compressedSize}"
        )
        return info
    }

    fun getThumbBytes(handle: Int): ByteArray {
        return transferObjectBytes(PtpCodes.OC_GetThumb, handle, "GetThumb")
    }

    fun getObjectBytes(handle: Int): ByteArray {
        return transferObjectBytes(PtpCodes.OC_GetObject, handle, "GetObjectBytes")
    }

    fun configureCanonEosMode(): Boolean {
        val remoteOk = runCatching {
            transactNoDataCommand(
                opCode = PtpCodes.OC_EOSSetRemoteMode,
                label = "EOSSetRemoteMode",
                params = intArrayOf(1)
            )
        }.getOrElse { error ->
            debug("canon: EOSSetRemoteMode failed: ${error.message}")
            false
        }
        val eventOk = runCatching {
            transactNoDataCommand(
                opCode = PtpCodes.OC_EOSSetEventMode,
                label = "EOSSetEventMode",
                params = intArrayOf(1)
            )
        }.getOrElse { error ->
            debug("canon: EOSSetEventMode failed: ${error.message}")
            false
        }
        val keepAliveOk = runCatching {
            transactNoDataCommand(
                opCode = PtpCodes.OC_EOSKeepDeviceOn,
                label = "EOSKeepDeviceOn",
                params = intArrayOf()
            )
        }.getOrElse { error ->
            debug("canon: EOSKeepDeviceOn failed: ${error.message}")
            false
        }
        val configured = remoteOk && eventOk
        debug("canon: eos-mode configured=$configured keepAlive=$keepAliveOk")
        return configured
    }

    fun configureSonySdioMode(): Boolean {
        // Matches sequoia-ptpy Sony.session(): connect(1), connect(2),
        // GetExtDeviceInfo, connect(3). All four must return OK.
        val step1Ok = runSonySdioStep("SonySDIOConnect[1]") {
            transactSonySdioRecv(
                opCode = PtpCodes.OC_SonySdioConnect,
                label = "SonySDIOConnect[1]",
                params = intArrayOf(1, 0, 0)
            )
        }
        pauseSonySdio()
        val step2Ok = runSonySdioStep("SonySDIOConnect[2]") {
            transactSonySdioRecv(
                opCode = PtpCodes.OC_SonySdioConnect,
                label = "SonySDIOConnect[2]",
                params = intArrayOf(2, 0, 0)
            )
        }
        pauseSonySdio()
        val extInfoOk = runSonySdioStep("SonySDIOGetExtDeviceInfo") {
            transactSonySdioRecv(
                opCode = PtpCodes.OC_SonySdioGetExtDeviceInfo,
                label = "SonySDIOGetExtDeviceInfo",
                params = intArrayOf(PtpCodes.SONY_EXT_DEVICE_INFO_VERSION)
            )
        }
        pauseSonySdio()
        val step3Ok = runSonySdioStep("SonySDIOConnect[3]") {
            transactSonySdioRecv(
                opCode = PtpCodes.OC_SonySdioConnect,
                label = "SonySDIOConnect[3]",
                params = intArrayOf(3, 0, 0)
            )
        }
        val configured = step1Ok && step2Ok && extInfoOk && step3Ok
        debug("sony: sdio-mode configured=$configured")
        return configured
    }

    fun enableSonyPcControl() {
        val priorityOk = runCatching {
            transactSendData(
                opCode = PtpCodes.OC_SonySetControlDeviceA,
                label = "SonySetPriorityMode",
                params = intArrayOf(PtpCodes.DPC_SonyPriorityMode),
                payload = byteArrayOf(1)
            )
        }.getOrDefault(false)
        pauseSonySdio()
        val transferOk = runCatching {
            transactSonySdioRecv(
                opCode = PtpCodes.OC_SonySetContentsTransferMode,
                label = "SonySetContentsTransferMode",
                params = intArrayOf(1)
            )
        }.getOrDefault(false)
        debug("sony: pc-control priority=$priorityOk contentsTransfer=$transferOk")
    }

    fun pollCanonEosEvents(): List<CanonEosEvent> {
        val tx = nextTxId()
        debug("cmd: EOSGetEvent tx=$tx")
        sendCommand(PtpCodes.OC_EOSGetEvent, tx)

        val (payload, response) = readMaybeDataAndResponse(2500)
        require(response.type == PtpCodes.CONTAINER_RESPONSE) {
            "EOSGetEvent expected RESPONSE container"
        }
        require(response.code == PtpCodes.RC_OK) {
            "EOSGetEvent failed with code 0x${response.code.toHex()}"
        }

        if (payload == null || payload.isEmpty()) {
            debug("data: EOSGetEvent tx=$tx empty")
            return emptyList()
        }

        val events = parseCanonEosEvents(payload)
        debug("data: EOSGetEvent tx=$tx events=${events.size}")
        return events
    }

    private fun writeBulk(bytes: ByteArray, timeoutMs: Int) {
        val conn = requireNotNull(connection)
        val endpoint = requireNotNull(bulkOut)
        val written = conn.bulkTransfer(endpoint, bytes, bytes.size, timeoutMs)
        require(written == bytes.size) { "Bulk write failed: $written/${bytes.size}" }
    }

    private fun readContainer(endpoint: UsbEndpoint, timeoutMs: Int): PtpContainer {
        return parseContainer(readRawContainer(endpoint, timeoutMs))
    }

    private fun readRawContainer(endpoint: UsbEndpoint, timeoutMs: Int): ByteArray {
        val conn = requireNotNull(connection)
        val collected = ByteArrayOutputStream()
        if (bulkInPending.isNotEmpty()) {
            collected.write(bulkInPending)
            bulkInPending = ByteArray(0)
        }

        val chunk = ByteArray(16 * 1024)
        var zeroLengthPackets = 0
        fun fillUntil(size: Int) {
            while (collected.size() < size) {
                val read = conn.bulkTransfer(endpoint, chunk, chunk.size, timeoutMs)
                if (read == 0) {
                    zeroLengthPackets += 1
                    require(zeroLengthPackets <= 3) { "Too many zero-length USB packets" }
                    continue
                }
                require(read > 0) { "Container read interrupted (${collected.size()}/$size)" }
                zeroLengthPackets = 0
                collected.write(chunk, 0, read)
            }
        }

        fillUntil(12)
        var all = collected.toByteArray()
        val totalLen = leInt(all, 0)
        require(totalLen in 12..64_000_000) { "Invalid container length $totalLen" }
        fillUntil(totalLen)
        all = collected.toByteArray()
        if (all.size > totalLen) {
            bulkInPending = all.copyOfRange(totalLen, all.size)
        }
        return all.copyOf(totalLen)
    }

    private fun readDataAndResponse(timeoutMs: Int): Triple<PtpContainer, ByteArray, PtpContainer> {
        val endpoint = requireNotNull(bulkIn)
        val firstRaw = readRawContainer(endpoint, timeoutMs)
        val secondRaw = readRawContainer(endpoint, timeoutMs)
        val first = parseContainer(firstRaw)
        val second = parseContainer(secondRaw)

        val dataContainer = if (first.type == PtpCodes.CONTAINER_DATA) first else second
        val responseContainer = if (first.type == PtpCodes.CONTAINER_RESPONSE) first else second
        val dataRaw = if (first.type == PtpCodes.CONTAINER_DATA) firstRaw else secondRaw
        val payload = if (dataRaw.size > 12) dataRaw.copyOfRange(12, dataRaw.size) else ByteArray(0)

        debug(
            "rsp2: dataType=${dataContainer.type} dataCode=0x${dataContainer.code.toHex()} " +
                "respType=${responseContainer.type} respCode=0x${responseContainer.code.toHex()}"
        )
        return Triple(dataContainer, payload, responseContainer)
    }

    private fun readMaybeDataAndResponse(timeoutMs: Int): Pair<ByteArray?, PtpContainer> {
        val endpoint = requireNotNull(bulkIn)
        val firstRaw = readRawContainer(endpoint, timeoutMs)
        val first = parseContainer(firstRaw)
        if (first.type == PtpCodes.CONTAINER_RESPONSE) {
            debug("rsp1: respType=${first.type} respCode=0x${first.code.toHex()} (no data)")
            return null to first
        }

        require(first.type == PtpCodes.CONTAINER_DATA) {
            "Expected DATA or RESPONSE container, got type=${first.type}"
        }

        val secondRaw = readRawContainer(endpoint, timeoutMs)
        val second = parseContainer(secondRaw)
        require(second.type == PtpCodes.CONTAINER_RESPONSE) {
            "Expected RESPONSE container after DATA, got type=${second.type}"
        }

        val payload = if (firstRaw.size > 12) firstRaw.copyOfRange(12, firstRaw.size) else ByteArray(0)
        debug(
            "rsp2: dataType=${first.type} dataCode=0x${first.code.toHex()} " +
                "respType=${second.type} respCode=0x${second.code.toHex()}"
        )
        return payload to second
    }

    private fun transferObjectBytes(opCode: Int, handle: Int, label: String): ByteArray {
        val tx = nextTxId()
        debug("cmd: $label tx=$tx handle=0x${handle.toHex()}")
        sendCommand(opCode, tx, intArrayOf(handle))

        val endpoint = requireNotNull(bulkIn)
        val raw = readRawContainer(endpoint, 10_000)
        val container = parseContainer(raw)
        require(container.type == PtpCodes.CONTAINER_DATA) {
            "$label expected DATA container, got type=${container.type} code=${PtpCodes.responseName(container.code)}"
        }
        val payload = if (raw.size > 12) raw.copyOfRange(12, raw.size) else ByteArray(0)

        val response = readResponse(5000)
        require(response.type == PtpCodes.CONTAINER_RESPONSE) {
            "$label expected RESPONSE container"
        }
        require(response.code == PtpCodes.RC_OK) {
            "$label failed with ${PtpCodes.responseName(response.code)}"
        }
        debug("data: $label tx=$tx handle=0x${handle.toHex()} bytes=${payload.size}")
        return payload
    }

    private fun runSonySdioStep(label: String, block: () -> Boolean): Boolean {
        return runCatching(block).getOrElse { error ->
            debug("sony: $label failed: ${error.message}")
            false
        }
    }

    private fun pauseSonySdio() {
        Thread.sleep(150)
    }

    private fun transactSonySdioRecv(
        opCode: Int,
        label: String,
        params: IntArray
    ): Boolean {
        val tx = nextTxId()
        debug("cmd: $label tx=$tx")
        sendCommand(opCode, tx, params)
        val (payload, response) = readMaybeDataAndResponse(5000)
        val ok = response.type == PtpCodes.CONTAINER_RESPONSE && response.code == PtpCodes.RC_OK
        debug(
            "rsp: $label tx=$tx code=${PtpCodes.responseName(response.code)} " +
                "ok=$ok payload=${payload?.size ?: 0}"
        )
        return ok
    }

    private fun tryOpenSession(tx: Int, sessionId: Int): Boolean {
        return runCatching {
            debug("cmd: OpenSession tx=$tx sessionId=$sessionId")
            sendCommand(PtpCodes.OC_OpenSession, tx, intArrayOf(sessionId))
            val response = readResponse(5000)
            debug("rsp: OpenSession tx=$tx code=${PtpCodes.responseName(response.code)}")
            response.code == PtpCodes.RC_OK || response.code == PtpCodes.RC_SessionAlreadyOpen
        }.getOrElse { error ->
            debug("OpenSession tx=$tx failed: ${error.message}")
            false
        }
    }

    private fun isSonyStorageRetry(error: Throwable): Boolean {
        val message = error.message ?: return false
        return message.contains("SessionNotOpen") ||
            message.contains("DeviceBusy") ||
            message.contains("OperationNotSupported") ||
            message.contains("StoreNotAvailable")
    }

    private fun transactSendData(
        opCode: Int,
        label: String,
        params: IntArray,
        payload: ByteArray
    ): Boolean {
        val tx = nextTxId()
        debug("cmd: $label tx=$tx")
        sendCommand(opCode, tx, params)
        val length = 12 + payload.size
        val container = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        container.putInt(length)
        container.putShort(PtpCodes.CONTAINER_DATA.toShort())
        container.putShort(opCode.toShort())
        container.putInt(tx)
        container.put(payload)
        writeBulk(container.array(), 3000)
        val response = readResponse(5000)
        val ok = response.type == PtpCodes.CONTAINER_RESPONSE && response.code == PtpCodes.RC_OK
        debug("rsp: $label tx=$tx code=${PtpCodes.responseName(response.code)} ok=$ok")
        return ok
    }

    private fun parseDeviceInfo(payload: ByteArray): PtpDeviceInfo {
        if (payload.size < 8) {
            return PtpDeviceInfo(
                standardVersion = -1,
                vendorExtensionId = -1,
                manufacturer = "",
                model = "",
                operations = emptySet()
            )
        }
        var offset = 0
        val standardVersion = leShort(payload, offset).toInt() and 0xFFFF
        offset += 2
        val vendorExtensionId = leInt(payload, offset)
        offset += 4
        offset += 2
        offset = readPtpString(payload, offset).second
        if (offset + 2 <= payload.size) offset += 2
        val (operations, afterOps) = readUint16Array(payload, offset)
        offset = afterOps
        repeat(4) {
            offset = readUint16Array(payload, offset).second
        }
        val (manufacturer, afterManufacturer) = readPtpString(payload, offset)
        val (model, _) = readPtpString(payload, afterManufacturer)
        return PtpDeviceInfo(
            standardVersion = standardVersion,
            vendorExtensionId = vendorExtensionId,
            manufacturer = manufacturer,
            model = model,
            operations = operations.toSet()
        )
    }

    private fun readUint16Array(data: ByteArray, offset: Int): Pair<List<Int>, Int> {
        if (offset + 4 > data.size) return emptyList<Int>() to data.size
        val count = leInt(data, offset).coerceIn(0, 4096)
        var pos = offset + 4
        val values = ArrayList<Int>(count)
        repeat(count) {
            if (pos + 2 > data.size) return values to data.size
            values.add(leShort(data, pos).toInt() and 0xFFFF)
            pos += 2
        }
        return values to pos
    }

    private fun transactNoDataCommand(
        opCode: Int,
        label: String,
        params: IntArray = intArrayOf(),
        timeoutMs: Int = 5000
    ): Boolean {
        val tx = nextTxId()
        debug("cmd: $label tx=$tx")
        sendCommand(opCode, tx, params)
        val response = readResponse(timeoutMs)
        val ok = response.type == PtpCodes.CONTAINER_RESPONSE && response.code == PtpCodes.RC_OK
        debug("rsp: $label tx=$tx code=0x${response.code.toHex()} ok=$ok")
        return ok
    }

    private fun parseContainer(raw: ByteArray): PtpContainer {
        require(raw.size >= 12) { "Container too small" }
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.int
        val type = bb.short.toInt() and 0xFFFF
        val code = bb.short.toInt() and 0xFFFF
        val transactionId = bb.int
        val paramCount = ((length - 12) / 4).coerceAtLeast(0)
        val params = IntArray(paramCount)
        repeat(paramCount) { idx ->
            params[idx] = bb.int
        }
        return PtpContainer(length, type, code, transactionId, params)
    }

    private fun parseCanonEosEvents(payload: ByteArray): List<CanonEosEvent> {
        val events = mutableListOf<CanonEosEvent>()
        var offset = 0
        while (offset + 8 <= payload.size) {
            val recordSize = leInt(payload, offset)
            if (recordSize < 8 || offset + recordSize > payload.size) {
                debug("canon: invalid EOS event record size=$recordSize at offset=$offset")
                break
            }

            val code = leInt(payload, offset + 4)
            val handle =
                when (code) {
                    PtpCodes.EOS_EC_ObjectAdded,
                    PtpCodes.EOS_EC_RequestObjectTransfer,
                    PtpCodes.EOS_EC_RequestObjectTransferDt,
                    PtpCodes.EOS_EC_ObjectAddedEx64,
                    PtpCodes.EOS_EC_RequestObjectTransfer64 -> {
                        if (recordSize >= 12) leInt(payload, offset + 8) else null
                    }
                    else -> null
                }

            events.add(CanonEosEvent(code = code, sizeBytes = recordSize, handle = handle))
            offset += recordSize
        }
        return events
    }

    private fun readPtpString(data: ByteArray, offset: Int): Pair<String, Int> {
        if (offset >= data.size) return "" to data.size
        val length = data[offset].toInt() and 0xFF
        if (length == 0) return "" to (offset + 1)
        val charCount = (length - 1).coerceAtLeast(0)
        val bytesNeeded = charCount * 2
        val start = offset + 1
        val end = (start + bytesNeeded).coerceAtMost(data.size)
        val value = if (end > start) {
            String(data, start, end - start, Charsets.UTF_16LE)
        } else {
            ""
        }
        val nextOffset = (offset + 1 + (length * 2)).coerceAtMost(data.size)
        return value.trimEnd('\u0000') to nextOffset
    }

    private fun debug(message: String) {
        onDebug?.invoke(message)
    }

    private fun Int.toHex(): String = this.toString(16).padStart(4, '0')

    private fun findPtpInterface(usbDevice: UsbDevice): UsbInterface? {
        for (i in 0 until usbDevice.interfaceCount) {
            val iface = usbDevice.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return iface
            }
        }
        return null
    }

    private fun leInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun leShort(data: ByteArray, offset: Int): Short {
        val value = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        return value.toShort()
    }
}
