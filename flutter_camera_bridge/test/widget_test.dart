import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_camera_bridge/flutter_camera_bridge.dart';

void main() {
  test('supported brands include Canon', () {
    expect(FlutterCameraBridge.supportedBrands, contains('Canon'));
  });
}
