# flutter_camera_bridge

Android Flutter plugin for connecting to supported DSLR cameras over USB OTG/PTP,
listing images, and loading thumbnails or full image bytes.

## Features

- Start and stop the DSLR bridge
- Detect supported camera connection state
- Listen for DSLR state, debug, and photo-detected events
- List camera images with paging support
- Load thumbnail bytes or full image bytes by object handle

## Supported brands

- Canon (EOS remote events)
- Sony (USB PTP / PC Remote SDIO handshake)
- Nikon (detection only; live session not implemented)

For Sony, set the camera USB mode to **PC Remote** (or **MTP** on older bodies).
PC Remote cameras require the plugin’s SDIO handshake before gallery and
photo-detect work reliably.

## Usage

```dart
final bridge = FlutterCameraBridge.instance;

await bridge.start();
final info = await bridge.getConnectedDeviceInfo();
final images = await bridge.listImages(limit: 30, offset: 0);
final thumb = await bridge.getThumbnailBytes(images.first.handle);
```

See `example/` for a complete browser app.
