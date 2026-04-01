import 'dart:typed_data';

sealed class DslrEvent {
  const DslrEvent();
}

class CameraDeviceInfo {
  const CameraDeviceInfo({
    required this.brand,
    required this.vendorId,
    required this.productId,
    this.deviceName,
  });

  final String brand;
  final int vendorId;
  final int productId;
  final String? deviceName;

  factory CameraDeviceInfo.fromMap(Map<String, dynamic> payload) {
    return CameraDeviceInfo(
      brand: payload['brand'] as String? ?? 'Unknown',
      vendorId: (payload['vendorId'] as num?)?.toInt() ?? -1,
      productId: (payload['productId'] as num?)?.toInt() ?? -1,
      deviceName: payload['deviceName'] as String?,
    );
  }
}

class CameraStorageItem {
  const CameraStorageItem({
    required this.handle,
    required this.storageId,
    required this.objectFormat,
    required this.compressedSize,
    required this.fileName,
    this.captureDate,
  });

  final int handle;
  final int storageId;
  final int objectFormat;
  final int compressedSize;
  final String fileName;
  final String? captureDate;

  factory CameraStorageItem.fromMap(Map<String, dynamic> payload) {
    return CameraStorageItem(
      handle: (payload['handle'] as num?)?.toInt() ?? -1,
      storageId: (payload['storageId'] as num?)?.toInt() ?? -1,
      objectFormat: (payload['objectFormat'] as num?)?.toInt() ?? -1,
      compressedSize: (payload['compressedSize'] as num?)?.toInt() ?? 0,
      fileName: payload['fileName'] as String? ?? 'Unknown',
      captureDate: payload['captureDate'] as String?,
    );
  }
}

class DslrStateEvent extends DslrEvent {
  const DslrStateEvent(this.state, {this.deviceInfo});

  final String state;
  final CameraDeviceInfo? deviceInfo;
}

class DslrErrorEvent extends DslrEvent {
  const DslrErrorEvent(this.message);

  final String message;
}

class DslrDebugEvent extends DslrEvent {
  const DslrDebugEvent(this.message);

  final String message;
}

class DslrPhotoEvent extends DslrEvent {
  const DslrPhotoEvent({
    required this.handle,
    required this.path,
    required this.size,
    required this.bytes,
    required this.brand,
    required this.vendorId,
    required this.productId,
  });

  final int handle;
  final String path;
  final int size;
  final Uint8List bytes;
  final String brand;
  final int vendorId;
  final int productId;
}
