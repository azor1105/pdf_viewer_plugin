import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// The direction in which the PDF pages scroll.
enum ScrollDirection { vertical, horizontal }

/// A widget that displays a PDF document using native Android PDF APIs.
///
/// On API 31+ with SDK extension >= 13, uses the Jetpack PdfViewerFragment
/// with full search, zoom, text selection and annotation support.
/// On older devices, falls back to the framework PdfRenderer.
///
/// Provide either [assetPath] (a Flutter asset) or [filePath] (absolute path).
class PdfViewer extends StatefulWidget {
  /// Path to a Flutter asset, e.g. "assets/pdf/document.pdf".
  final String? assetPath;

  /// Absolute file system path, e.g. from a file picker.
  final String? filePath;

  /// The scroll direction for the PDF pages (default: vertical).
  final ScrollDirection scrollDirection;

  /// The page index (0-based) to open the document at.
  final int initialPage;

  /// Called whenever the visible page changes. The value is the 0-based index.
  final ValueChanged<int>? onPageChanged;

  const PdfViewer({
    super.key,
    this.assetPath,
    this.filePath,
    this.scrollDirection = ScrollDirection.horizontal,
    this.initialPage = 0,
    this.onPageChanged,
  }) : assert(
         assetPath != null || filePath != null,
         'Either assetPath or filePath must be provided',
       );

  @override
  State<PdfViewer> createState() => _PdfViewerState();
}

class _PdfViewerState extends State<PdfViewer> {
  late final MethodChannel _channel;

  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid) {
      return const Center(
        child: Text('PDF viewer is only supported on Android'),
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

    return AndroidView(
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

  void _onPlatformViewCreated(int id) {
    _channel = MethodChannel('pdf_viewer_plugin_view/$id');
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onPageChanged') {
        widget.onPageChanged?.call(call.arguments as int);
      }
    });
  }
}
// TODO:
// 1. horizontal and vertical scroll support
// 2. support for text selection and copying to clipboard

// LATER:
// 4. support for searching text in the PDF and highlighting search results
// 5. support for annotations and comments
