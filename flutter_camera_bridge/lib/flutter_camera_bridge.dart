import 'dart:typed_data';

import 'camera_connector.dart';
import 'dslr/dslr_models.dart';

export 'dslr/dslr_models.dart';

class FlutterCameraBridge {
  FlutterCameraBridge._();

  static final FlutterCameraBridge instance = FlutterCameraBridge._();
  static const List<String> supportedBrands =
      AndroidPtpCameraConnector.supportedBrands;

  final AndroidPtpCameraConnector _connector =
      AndroidPtpCameraConnector.instance;

  Future<void> start() => _connector.start();
  Future<void> stop() => _connector.stop();
  Future<bool> isConnected() => _connector.isConnected();
  Future<CameraDeviceInfo?> getConnectedDeviceInfo() =>
      _connector.getConnectedDeviceInfo();
  Future<List<CameraStorageItem>> listImages({
    int limit = 30,
    int offset = 0,
  }) => _connector.listImages(limit: limit, offset: offset);
  Future<Uint8List> getThumbnailBytes(int handle) =>
      _connector.getThumbnailBytes(handle);
  Future<Uint8List> getImageBytes(int handle) =>
      _connector.getImageBytes(handle);
  Stream<DslrEvent> events() => _connector.events();
}
