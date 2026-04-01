import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'camera_connector.dart';
import 'dslr/dslr_models.dart';

void main() {
  runApp(const DslrApp());
}

class DslrApp extends StatelessWidget {
  const DslrApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'QRPhoto DSLR',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      home: const DslrHomePage(),
    );
  }
}

class DslrHomePage extends StatefulWidget {
  const DslrHomePage({super.key});

  @override
  State<DslrHomePage> createState() => _DslrHomePageState();
}

class _DslrHomePageState extends State<DslrHomePage> {
  static const int _maxDebugLines = 60;
  static const int _pageSize = 30;
  static const Duration _autoPollInterval = Duration(seconds: 8);

  final AndroidPtpCameraConnector _connector =
      AndroidPtpCameraConnector.instance;
  final List<String> _debugLogs = <String>[];
  final ScrollController _gridScrollController = ScrollController();
  final Map<int, Future<Uint8List>> _thumbnailFutures =
      <int, Future<Uint8List>>{};
  Future<void> _thumbnailQueue = Future<void>.value();
  StreamSubscription<DslrEvent>? _subscription;
  Timer? _autoPollTimer;

  List<CameraStorageItem> _images = <CameraStorageItem>[];
  DslrPhotoEvent? _latestPhoto;
  String _connectionState = 'idle';
  CameraDeviceInfo? _deviceInfo;
  String? _lastError;
  bool _isLoadingImages = false;
  bool _isAutoPolling = false;
  bool _hasMoreImages = true;
  bool _autoDetectNewImages = true;

  @override
  void initState() {
    super.initState();
    _subscription = _connector.events().listen(_onDslrEvent);
    _gridScrollController.addListener(_onGridScroll);
    _startConnector();
  }

  void _onGridScroll() {
    if (!_gridScrollController.hasClients ||
        _isLoadingImages ||
        !_hasMoreImages)
      return;
    final position = _gridScrollController.position;
    if (position.pixels >= position.maxScrollExtent - 280) {
      _loadImages(showSnackbar: false, append: true);
    }
  }

  Future<void> _startConnector() async {
    try {
      await _connector.start();
      final info = await _connector.getConnectedDeviceInfo();
      if (!mounted) return;
      setState(() {
        _deviceInfo = info;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _lastError = 'Failed to start camera connector: $e';
      });
    }
  }

  void _onDslrEvent(DslrEvent event) {
    if (!mounted) return;
    setState(() {
      if (event is DslrStateEvent) {
        _connectionState = event.state;
        _deviceInfo = event.deviceInfo ?? _deviceInfo;
        if (event.state == 'connected' ||
            event.state == 'connecting' ||
            event.state == 'starting' ||
            event.state == 'device_attached') {
          _lastError = null;
        }
      } else if (event is DslrErrorEvent) {
        _lastError = event.message;
      } else if (event is DslrDebugEvent) {
        _debugLogs.insert(0, event.message);
        if (_debugLogs.length > _maxDebugLines) {
          _debugLogs.removeRange(_maxDebugLines, _debugLogs.length);
        }
      } else if (event is DslrPhotoEvent) {
        _latestPhoto = event;
        _lastError = null;
      }
    });
    _syncAutoPollState();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _autoPollTimer?.cancel();
    _gridScrollController.dispose();
    _connector.stop();
    super.dispose();
  }

  Future<void> _loadImages({
    required bool showSnackbar,
    bool append = false,
  }) async {
    if (_isLoadingImages) return;
    final int requestOffset = append ? _images.length : 0;
    setState(() {
      _isLoadingImages = true;
      if (!append) {
        _hasMoreImages = true;
      }
      _lastError = null;
    });

    try {
      final images = await _connector.listImages(
        limit: _pageSize,
        offset: requestOffset,
      );
      if (!mounted) return;
      setState(() {
        _images = append ? <CameraStorageItem>[..._images, ...images] : images;
        _hasMoreImages = images.length == _pageSize;
        if (!append) {
          _thumbnailFutures.clear();
        }
      });
      if (showSnackbar) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              append
                  ? 'Loaded ${images.length} more image(s) (${_images.length} total)'
                  : 'Loaded ${images.length} image(s) from camera',
            ),
          ),
        );
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _lastError = 'Failed to load camera images: $e';
      });
    } finally {
      if (!mounted) return;
      setState(() {
        _isLoadingImages = false;
      });
    }
  }

  Future<void> _autoCheckForNewImages() async {
    if (!_autoDetectNewImages ||
        _connectionState != 'connected' ||
        _isLoadingImages ||
        _isAutoPolling) {
      return;
    }
    _isAutoPolling = true;
    try {
      final latestPage = await _connector.listImages(
        limit: _pageSize,
        offset: 0,
      );
      if (!mounted || latestPage.isEmpty) return;
      final currentHandles = _images.map((e) => e.handle).toSet();
      final hasNew = latestPage.any((e) => !currentHandles.contains(e.handle));
      if (!hasNew) return;

      setState(() {
        final merged = <int, CameraStorageItem>{};
        for (final item in latestPage) {
          merged[item.handle] = item;
        }
        for (final item in _images) {
          merged.putIfAbsent(item.handle, () => item);
        }
        _images = merged.values.toList();
        _thumbnailFutures.clear();
      });

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('New image detected and added')),
        );
      }
    } catch (_) {
      // Silent in auto mode; keep UI smooth and avoid noisy transient USB errors.
    } finally {
      _isAutoPolling = false;
    }
  }

  void _syncAutoPollState() {
    final shouldRun = _autoDetectNewImages && _connectionState == 'connected';
    if (!shouldRun) {
      _autoPollTimer?.cancel();
      _autoPollTimer = null;
      return;
    }
    if (_autoPollTimer != null) return;
    _autoPollTimer = Timer.periodic(_autoPollInterval, (_) {
      _autoCheckForNewImages();
    });
  }

  Future<void> _copyDebugLogsToClipboard() async {
    if (_debugLogs.isEmpty) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('No debug logs to copy yet')),
      );
      return;
    }
    final buffer =
        StringBuffer()
          ..writeln('State: $_connectionState')
          ..writeln(
            _deviceInfo != null
                ? 'Camera: ${_deviceInfo!.brand} '
                    '(VID=0x${_deviceInfo!.vendorId.toRadixString(16)}, '
                    'PID=0x${_deviceInfo!.productId.toRadixString(16)})'
                : 'Camera: (none)',
          );
    if (_lastError != null) {
      buffer.writeln('Error: $_lastError');
    }
    buffer.writeln('--- debug log (newest first, as on screen) ---');
    for (final String line in _debugLogs) {
      buffer.writeln(line);
    }
    await Clipboard.setData(ClipboardData(text: buffer.toString()));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Copied ${_debugLogs.length} log line(s) to clipboard'),
      ),
    );
  }

  Future<void> _openImage(CameraStorageItem item) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (BuildContext context) {
          return _ImagePreviewPage(
            title: item.fileName,
            loader: () => _connector.getImageBytes(item.handle),
          );
        },
      ),
    );
  }

  Future<Uint8List> _thumbnailFutureFor(CameraStorageItem item) {
    return _thumbnailFutures.putIfAbsent(item.handle, () {
      final completer = Completer<Uint8List>();
      _thumbnailQueue = _thumbnailQueue.then((_) async {
        try {
          final bytes = await _connector.getThumbnailBytes(item.handle);
          completer.complete(bytes);
        } catch (e, st) {
          completer.completeError(e, st);
        }
      });
      return completer.future;
    });
  }

  String _formatCaptureDate(String? raw) {
    if (raw == null || raw.isEmpty) return 'Unknown date';
    if (raw.length >= 15) {
      final year = raw.substring(0, 4);
      final month = raw.substring(4, 6);
      final day = raw.substring(6, 8);
      final hour = raw.substring(9, 11);
      final minute = raw.substring(11, 13);
      return '$year-$month-$day $hour:$minute';
    }
    return raw;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('PTP Camera Browser'),
        actions: <Widget>[
          IconButton(
            icon: const Icon(Icons.copy_all_outlined),
            tooltip: 'Copy debug logs',
            onPressed: _copyDebugLogsToClipboard,
          ),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Container(
            color: theme.colorScheme.surfaceContainerHighest,
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text('State: $_connectionState'),
                if (_deviceInfo != null)
                  Text(
                    'Camera: ${_deviceInfo!.brand} '
                    '(VID=0x${_deviceInfo!.vendorId.toRadixString(16)}, '
                    'PID=0x${_deviceInfo!.productId.toRadixString(16)})',
                  ),
                if (_lastError != null)
                  Text(
                    'Error: $_lastError',
                    style: TextStyle(color: theme.colorScheme.error),
                  ),
                if (_latestPhoto != null)
                  Text(
                    'Last live photo event: handle=${_latestPhoto!.handle}, bytes=${_latestPhoto!.bytes.length}',
                  ),
                const SizedBox(height: 8),
                Row(
                  children: <Widget>[
                    FilledButton.icon(
                      onPressed:
                          _isLoadingImages || _connectionState != 'connected'
                              ? null
                              : () => _loadImages(
                                showSnackbar: true,
                                append: false,
                              ),
                      icon:
                          _isLoadingImages
                              ? const SizedBox.square(
                                dimension: 16,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                              : const Icon(Icons.refresh),
                      label: Text(_images.isEmpty ? 'Load Images' : 'Refresh'),
                    ),
                    const SizedBox(width: 12),
                    Text(
                      _hasMoreImages
                          ? 'Showing ${_images.length}+ image(s)'
                          : 'Showing ${_images.length} image(s)',
                    ),
                    const SizedBox(width: 12),
                    FilterChip(
                      label: const Text('Auto-detect new'),
                      selected: _autoDetectNewImages,
                      onSelected: (bool value) {
                        setState(() {
                          _autoDetectNewImages = value;
                        });
                        _syncAutoPollState();
                      },
                    ),
                  ],
                ),
              ],
            ),
          ),
          Expanded(
            child:
                _images.isEmpty
                    ? Center(
                      child: Text(
                        _connectionState == 'connected'
                            ? 'Connected. Tap "Load Images" to browse the camera storage.'
                            : 'Connect Canon, Sony, or Nikon via OTG.',
                        textAlign: TextAlign.center,
                      ),
                    )
                    : Column(
                      children: <Widget>[
                        Expanded(
                          child: GridView.builder(
                            controller: _gridScrollController,
                            padding: const EdgeInsets.all(8),
                            gridDelegate:
                                const SliverGridDelegateWithFixedCrossAxisCount(
                                  crossAxisCount: 3,
                                  crossAxisSpacing: 8,
                                  mainAxisSpacing: 8,
                                  childAspectRatio: 0.78,
                                ),
                            itemCount: _images.length,
                            itemBuilder: (BuildContext context, int index) {
                              final item = _images[index];
                              return InkWell(
                                onTap: () => _openImage(item),
                                child: Card(
                                  clipBehavior: Clip.antiAlias,
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.stretch,
                                    children: <Widget>[
                                      Expanded(
                                        child: Container(
                                          color:
                                              theme
                                                  .colorScheme
                                                  .surfaceContainerHigh,
                                          child: FutureBuilder<Uint8List>(
                                            future: _thumbnailFutureFor(item),
                                            builder: (
                                              BuildContext context,
                                              AsyncSnapshot<Uint8List> snapshot,
                                            ) {
                                              if (snapshot.connectionState !=
                                                  ConnectionState.done) {
                                                return const Center(
                                                  child: SizedBox.square(
                                                    dimension: 18,
                                                    child:
                                                        CircularProgressIndicator(
                                                          strokeWidth: 2,
                                                        ),
                                                  ),
                                                );
                                              }
                                              if (snapshot.hasError ||
                                                  snapshot.data == null ||
                                                  snapshot.data!.isEmpty) {
                                                return const Center(
                                                  child: Icon(
                                                    Icons.broken_image_outlined,
                                                    size: 28,
                                                  ),
                                                );
                                              }
                                              return Image.memory(
                                                snapshot.data!,
                                                fit: BoxFit.cover,
                                                gaplessPlayback: true,
                                              );
                                            },
                                          ),
                                        ),
                                      ),
                                      Padding(
                                        padding: const EdgeInsets.all(6),
                                        child: Column(
                                          crossAxisAlignment:
                                              CrossAxisAlignment.start,
                                          children: <Widget>[
                                            Text(
                                              item.fileName,
                                              maxLines: 1,
                                              overflow: TextOverflow.ellipsis,
                                              style:
                                                  theme.textTheme.labelMedium,
                                            ),
                                            Text(
                                              _formatCaptureDate(
                                                item.captureDate,
                                              ),
                                              maxLines: 1,
                                              overflow: TextOverflow.ellipsis,
                                              style: theme.textTheme.bodySmall,
                                            ),
                                            Text(
                                              'Tap to open',
                                              maxLines: 1,
                                              overflow: TextOverflow.ellipsis,
                                              style: theme.textTheme.bodySmall,
                                            ),
                                          ],
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                        if (_isLoadingImages)
                          const Padding(
                            padding: EdgeInsets.only(bottom: 8),
                            child: SizedBox.square(
                              dimension: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                          )
                        else if (_hasMoreImages &&
                            _connectionState == 'connected')
                          Padding(
                            padding: const EdgeInsets.only(bottom: 8),
                            child: FilledButton.tonal(
                              onPressed:
                                  () => _loadImages(
                                    showSnackbar: true,
                                    append: true,
                                  ),
                              child: const Text('Load more'),
                            ),
                          ),
                      ],
                    ),
          ),
          Container(
            height: 140,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            color: Colors.black,
            child:
                _debugLogs.isEmpty
                    ? const Align(
                      alignment: Alignment.centerLeft,
                      child: Text(
                        'Debug logs will appear here...',
                        style: TextStyle(
                          color: Colors.white70,
                          fontFamily: 'monospace',
                        ),
                      ),
                    )
                    : ListView.builder(
                      itemCount: _debugLogs.length,
                      itemBuilder: (BuildContext context, int index) {
                        return Padding(
                          padding: const EdgeInsets.only(bottom: 3),
                          child: Text(
                            _debugLogs[index],
                            style: const TextStyle(
                              color: Colors.white70,
                              fontSize: 11,
                              fontFamily: 'monospace',
                            ),
                          ),
                        );
                      },
                    ),
          ),
        ],
      ),
    );
  }
}

class _ImagePreviewPage extends StatelessWidget {
  const _ImagePreviewPage({required this.title, required this.loader});

  final String title;
  final Future<Uint8List> Function() loader;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: FutureBuilder<Uint8List>(
        future: loader(),
        builder: (BuildContext context, AsyncSnapshot<Uint8List> snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Center(
              child: Text('Failed to load image: ${snapshot.error}'),
            );
          }
          final bytes = snapshot.data;
          if (bytes == null || bytes.isEmpty) {
            return const Center(child: Text('No image data returned.'));
          }
          return InteractiveViewer(
            minScale: 0.8,
            maxScale: 4.0,
            child: Center(
              child: Image.memory(
                bytes,
                fit: BoxFit.contain,
                gaplessPlayback: true,
              ),
            ),
          );
        },
      ),
    );
  }
}
