import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' show CircularProgressIndicator;
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// The direction in which the PDF pages scroll.
enum ScrollDirection { vertical, horizontal }

/// Controls a [PdfViewer] widget.
///
/// Attach a controller via [PdfViewer.controller] to read the current page and
/// change the scroll direction at runtime without rebuilding the widget tree.
///
/// ```dart
/// final _controller = PdfController();
///
/// @override
/// void dispose() {
///   _controller.dispose();
///   super.dispose();
/// }
///
/// // Read current page (0-based):
/// print(_controller.currentPage);
///
/// // Change scroll direction at runtime:
/// _controller.setScrollDirection(ScrollDirection.horizontal);
/// ```
class PdfController extends ChangeNotifier {
  MethodChannel? _channel;
  int _currentPage = 0;
  ScrollDirection _scrollDirection = ScrollDirection.horizontal;

  /// The current visible page index (0-based).
  int get currentPage => _currentPage;

  /// The current scroll direction of the attached viewer.
  ScrollDirection get scrollDirection => _scrollDirection;

  /// Changes the scroll direction of the attached [PdfViewer] at runtime.
  ///
  /// Has no effect if no viewer is currently attached.
  Future<void> setScrollDirection(ScrollDirection direction) async {
    _scrollDirection = direction;
    notifyListeners();
    await _channel?.invokeMethod('setScrollDirection', direction.name);
  }

  /// Jumps to [page] (0-based) in the attached [PdfViewer].
  ///
  /// Has no effect if no viewer is currently attached.
  Future<void> jumpToPage(int page) async {
    _currentPage = page;
    notifyListeners();
    await _channel?.invokeMethod('jumpToPage', page);
  }

  void _attach(MethodChannel channel, ScrollDirection initialDirection) {
    _channel = channel;
    _scrollDirection = initialDirection;
  }

  void _detach() {
    _channel = null;
  }

  void _updatePage(int page) {
    if (_currentPage == page) return;
    _currentPage = page;
    notifyListeners();
  }
}

/// A widget that displays a PDF document using native platform PDF APIs.
///
/// On Android, uses the framework PdfRenderer for rendering.
/// On iOS, uses Apple PDFKit (PDFView) available since iOS 12.
///
/// Use the named constructors to be explicit about the source:
/// - [PdfViewer.asset] — Flutter asset bundled with the app.
/// - [PdfViewer.file] — Absolute path on the device's storage.
class PdfViewer extends StatefulWidget {
  /// Path to a Flutter asset, e.g. "assets/pdf/document.pdf".
  final String? assetPath;

  /// Absolute file system path, e.g. from a file picker.
  final String? filePath;

  /// The scroll direction for the PDF pages (default: horizontal).
  final ScrollDirection scrollDirection;

  /// The page index (0-based) to open the document at.
  final int initialPage;

  /// Called whenever the visible page changes. The value is the 0-based index.
  final ValueChanged<int>? onPageChanged;

  /// Called once when the PDF document has finished loading.
  ///
  /// Use this to react to the document being ready, e.g. to update UI outside
  /// the viewer. The viewer itself already shows a built-in loading indicator
  /// (see [loadingWidget]) until the document is loaded.
  final VoidCallback? onDocumentLoaded;

  /// Widget shown over the viewer while the document is loading.
  ///
  /// Defaults to a centred [CircularProgressIndicator] on a white background.
  /// Pass `const SizedBox.shrink()` to disable the built-in loader entirely.
  final Widget? loadingWidget;

  /// Optional controller for programmatic access to the viewer.
  final PdfController? controller;

  /// Opens a PDF bundled as a Flutter asset.
  ///
  /// [assetPath] must be a path registered in `pubspec.yaml`, e.g.
  /// `"assets/pdf/document.pdf"`.
  const PdfViewer.asset(
    String this.assetPath, {
    super.key,
    this.controller,
    this.scrollDirection = ScrollDirection.vertical,
    this.initialPage = 0,
    this.onPageChanged,
    this.onDocumentLoaded,
    this.loadingWidget,
  }) : filePath = null;

  /// Opens a PDF from an absolute path on the device's storage.
  ///
  /// [filePath] must be a full file-system path, e.g.
  /// `"/data/user/0/com.example.app/app_flutter/document.pdf"`.
  const PdfViewer.file(
    String this.filePath, {
    super.key,
    this.controller,
    this.scrollDirection = ScrollDirection.horizontal,
    this.initialPage = 0,
    this.onPageChanged,
    this.onDocumentLoaded,
    this.loadingWidget,
  }) : assetPath = null;

  /// Low-level constructor — prefer [PdfViewer.asset] or [PdfViewer.file].
  const PdfViewer({
    super.key,
    this.assetPath,
    this.filePath,
    this.controller,
    this.scrollDirection = ScrollDirection.horizontal,
    this.initialPage = 0,
    this.onPageChanged,
    this.onDocumentLoaded,
    this.loadingWidget,
  }) : assert(
         assetPath != null || filePath != null,
         'Either assetPath or filePath must be provided',
       );

  @override
  State<PdfViewer> createState() => _PdfViewerState();
}

class _PdfViewerState extends State<PdfViewer> {
  MethodChannel? _channel;
  bool _isLoaded = false;

  @override
  void didUpdateWidget(PdfViewer oldWidget) {
    super.didUpdateWidget(oldWidget);
    // Hot-reload / setState fix: when scrollDirection changes on the widget,
    // push the new value to the native side via the method channel.
    if (oldWidget.scrollDirection != widget.scrollDirection) {
      _channel?.invokeMethod('setScrollDirection', widget.scrollDirection.name);
      widget.controller?._scrollDirection = widget.scrollDirection;
    }
  }

  @override
  void dispose() {
    widget.controller?._detach();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid && !Platform.isIOS) {
      return const Center(
        child: Text('PDF viewer is not supported on this platform'),
      );
    }

    final Map<String, dynamic> creationParams = {};
    if (widget.assetPath != null) {
      creationParams['assetPath'] = widget.assetPath;
    } else if (widget.filePath != null) {
      creationParams['filePath'] = widget.filePath;
    }
    creationParams['scrollDirection'] = widget.scrollDirection.name;
    creationParams['initialPage'] = widget.initialPage;

    final Widget platformView;
    if (Platform.isAndroid) {
      platformView = AndroidView(
        viewType: 'pdf_viewer_plugin_view',
        layoutDirection: TextDirection.ltr,
        creationParams: creationParams,
        creationParamsCodec: const StandardMessageCodec(),
        onPlatformViewCreated: _onPlatformViewCreated,
        gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
          Factory<VerticalDragGestureRecognizer>(
            () => VerticalDragGestureRecognizer(),
          ),
          Factory<HorizontalDragGestureRecognizer>(
            () => HorizontalDragGestureRecognizer(),
          ),
          Factory<ScaleGestureRecognizer>(() => ScaleGestureRecognizer()),
          Factory<EagerGestureRecognizer>(() => EagerGestureRecognizer()),
        },
      );
    } else {
      // iOS
      platformView = UiKitView(
        viewType: 'pdf_viewer_plugin_view',
        layoutDirection: TextDirection.ltr,
        creationParams: creationParams,
        creationParamsCodec: const StandardMessageCodec(),
        onPlatformViewCreated: _onPlatformViewCreated,
        gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
          Factory<VerticalDragGestureRecognizer>(
            () => VerticalDragGestureRecognizer(),
          ),
          Factory<HorizontalDragGestureRecognizer>(
            () => HorizontalDragGestureRecognizer(),
          ),
          Factory<ScaleGestureRecognizer>(() => ScaleGestureRecognizer()),
          Factory<EagerGestureRecognizer>(() => EagerGestureRecognizer()),
        },
      );
    }

    return Stack(
      fit: StackFit.expand,
      children: [
        platformView,
        if (!_isLoaded)
          ColoredBox(
            color: const Color(0xFFFFFFFF),
            child: Center(
              child: widget.loadingWidget ?? const CircularProgressIndicator(),
            ),
          ),
      ],
    );
  }

  void _onPlatformViewCreated(int id) {
    final channel = MethodChannel('pdf_viewer_plugin_view/$id');
    _channel = channel;
    widget.controller?._attach(channel, widget.scrollDirection);
    channel.setMethodCallHandler((call) async {
      if (call.method == 'onPageChanged') {
        final page = call.arguments as int;
        widget.onPageChanged?.call(page);
        widget.controller?._updatePage(page);
      } else if (call.method == 'onDocumentLoaded') {
        if (mounted && !_isLoaded) {
          setState(() => _isLoaded = true);
          widget.onDocumentLoaded?.call();
        }
      }
    });
  }
}
// TODO:
// 1. develop PDF viewer for iOS using PDFKit and add platform detection to show appropriate viewer on each platform
// 2. support for text selection and copying to clipboard

// LATER:
// 4. support for searching text in the PDF and highlighting search results
// 5. support for annotations and comments
