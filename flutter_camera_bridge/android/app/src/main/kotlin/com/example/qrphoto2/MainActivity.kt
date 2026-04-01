package com.example.flutter_camera_bridge

import com.example.flutter_camera_bridge.dslr.DslrUsbController
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : FlutterActivity(), MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private lateinit var dslrController: DslrUsbController
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        dslrController = DslrUsbController(applicationContext) { payload ->
            runOnUiThread {
                eventSink?.success(payload)
            }
        }

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dslr_otg/methods")
        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, "dslr_otg/events")
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> {
                dslrController.start()
                result.success(true)
            }
            "stop" -> {
                dslrController.stop()
                result.success(true)
            }
            "isConnected" -> result.success(dslrController.isConnected())
            "getConnectedDeviceInfo" -> result.success(dslrController.getConnectedDeviceInfo())
            "listImages" -> {
                val limit = (call.argument<Number>("limit")?.toInt()) ?: 30
                val offset = (call.argument<Number>("offset")?.toInt()) ?: 0
                ioScope.launch {
                    runCatching { dslrController.listImages(limit = limit, offset = offset) }
                        .onSuccess { images ->
                            runOnUiThread { result.success(images) }
                        }
                        .onFailure { error ->
                            runOnUiThread {
                                result.error("LIST_IMAGES_FAILED", error.message, null)
                            }
                        }
                }
            }
            "getThumbnailBytes" -> {
                val handle = (call.argument<Number>("handle")?.toInt())
                if (handle == null) {
                    result.error("BAD_ARGS", "Missing image handle", null)
                    return
                }
                ioScope.launch {
                    runCatching { dslrController.getThumbnailBytes(handle) }
                        .onSuccess { bytes ->
                            runOnUiThread { result.success(bytes) }
                        }
                        .onFailure { error ->
                            runOnUiThread {
                                result.error("GET_THUMB_FAILED", error.message, null)
                            }
                        }
                }
            }
            "getImageBytes" -> {
                val handle = (call.argument<Number>("handle")?.toInt())
                if (handle == null) {
                    result.error("BAD_ARGS", "Missing image handle", null)
                    return
                }
                ioScope.launch {
                    runCatching { dslrController.getImageBytes(handle) }
                        .onSuccess { bytes ->
                            runOnUiThread { result.success(bytes) }
                        }
                        .onFailure { error ->
                            runOnUiThread {
                                result.error("GET_IMAGE_FAILED", error.message, null)
                            }
                        }
                }
            }
            else -> result.notImplemented()
        }
    }

    override fun onDestroy() {
        ioScope.cancel()
        dslrController.stop()
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        super.onDestroy()
    }
}
