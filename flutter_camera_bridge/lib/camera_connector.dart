import 'dart:typed_data';

import 'dslr/dslr_bridge.dart';
import 'dslr/dslr_models.dart';

class AndroidPtpCameraConnector {
  AndroidPtpCameraConnector._();

  static final AndroidPtpCameraConnector instance =
      AndroidPtpCameraConnector._();
  static const List<String> supportedBrands = <String>[
    'Canon',
    'Sony',
    'Nikon',
  ];

  final DslrBridge _bridge = DslrBridge();

  Future<void> start() => _bridge.start();
  Future<void> stop() => _bridge.stop();
  Future<bool> isConnected() => _bridge.isConnected();
  Future<CameraDeviceInfo?> getConnectedDeviceInfo() =>
      _bridge.getConnectedDeviceInfo();
  Future<List<CameraStorageItem>> listImages({
    int limit = 30,
    int offset = 0,
  }) => _bridge.listImages(limit: limit, offset: offset);
  Future<Uint8List> getThumbnailBytes(int handle) =>
      _bridge.getThumbnailBytes(handle);
  Future<Uint8List> getImageBytes(int handle) => _bridge.getImageBytes(handle);
  Stream<DslrEvent> events() => _bridge.events();
}
