import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'dslr_models.dart';

class DslrBridge {
  static const MethodChannel _methodChannel = MethodChannel('dslr_otg/methods');
  static const EventChannel _eventChannel = EventChannel('dslr_otg/events');

  Stream<DslrEvent>? _stream;

  Future<void> start() async {
    await _methodChannel.invokeMethod<void>('start');
  }

  Future<void> stop() async {
    await _methodChannel.invokeMethod<void>('stop');
  }

  Future<bool> isConnected() async {
    return (await _methodChannel.invokeMethod<bool>('isConnected')) ?? false;
  }

  Future<CameraDeviceInfo?> getConnectedDeviceInfo() async {
    final raw = await _methodChannel.invokeMethod<dynamic>(
      'getConnectedDeviceInfo',
    );
    if (raw == null) return null;
    return CameraDeviceInfo.fromMap(Map<String, dynamic>.from(raw as Map));
  }

  Future<List<CameraStorageItem>> listImages({
    int limit = 30,
    int offset = 0,
  }) async {
    final raw = await _methodChannel.invokeMethod<dynamic>(
      'listImages',
      <String, dynamic>{'limit': limit, 'offset': offset},
    );
    final list = List<dynamic>.from(raw as List<dynamic>? ?? const <dynamic>[]);
    return list
        .map(
          (dynamic item) =>
              CameraStorageItem.fromMap(Map<String, dynamic>.from(item as Map)),
        )
        .toList();
  }

  Future<Uint8List> getThumbnailBytes(int handle) async {
    final raw = await _methodChannel.invokeMethod<Uint8List>(
      'getThumbnailBytes',
      <String, dynamic>{'handle': handle},
    );
    return raw ?? Uint8List(0);
  }

  Future<Uint8List> getImageBytes(int handle) async {
    final raw = await _methodChannel.invokeMethod<Uint8List>(
      'getImageBytes',
      <String, dynamic>{'handle': handle},
    );
    return raw ?? Uint8List(0);
  }

  Stream<DslrEvent> events() {
    _stream ??=
        _eventChannel.receiveBroadcastStream().asyncMap((dynamic raw) async {
          final payload = Map<String, dynamic>.from(raw as Map);
          final type = payload['type'] as String? ?? '';

          if (type == 'state') {
            final hasDevice =
                payload['vendorId'] != null ||
                payload['productId'] != null ||
                payload['brand'] != null;
            return DslrStateEvent(
              payload['state'] as String? ?? 'unknown',
              deviceInfo: hasDevice ? CameraDeviceInfo.fromMap(payload) : null,
            );
          }
          if (type == 'error') {
            return DslrErrorEvent(
              payload['message'] as String? ?? 'Unknown DSLR error',
            );
          }
          if (type == 'debug') {
            return DslrDebugEvent(
              payload['message'] as String? ?? 'Unknown DSLR debug event',
            );
          }
          if (type == 'photo') {
            final path = payload['path'] as String;
            final bytes = await compute(_readBytesFromPath, path);
            return DslrPhotoEvent(
              handle: (payload['handle'] as num?)?.toInt() ?? -1,
              path: path,
              size: (payload['size'] as num?)?.toInt() ?? bytes.length,
              bytes: bytes,
              brand: payload['brand'] as String? ?? 'Unknown',
              vendorId: (payload['vendorId'] as num?)?.toInt() ?? -1,
              productId: (payload['productId'] as num?)?.toInt() ?? -1,
            );
          }

          return const DslrErrorEvent('Unknown native DSLR event');
        }).asBroadcastStream();

    return _stream!;
  }
}

Future<Uint8List> _readBytesFromPath(String path) async {
  return File(path).readAsBytes();
}
