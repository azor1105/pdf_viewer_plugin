import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// Scroll direction for the PDF viewer.
enum PdfScrollDirection {
  /// Pages are laid out vertically (default).
  vertical,

  /// Pages are laid out horizontally with snap-to-page behavior.
  horizontal,
}

/// A widget that displays a PDF document using native Android PDF APIs.
///
/// On API 31+ with SDK extension >= 13, uses the Jetpack PdfViewerFragment
/// with full search, zoom, text selection and annotation support.
/// On older devices, falls back to the framework PdfRenderer with
/// pinch-to-zoom, a page scrubber, and page change notifications.
///
/// Provide either [assetPath] (a Flutter asset) or [filePath] (absolute path).
class PdfViewer extends StatefulWidget {
  /// Path to a Flutter asset, e.g. "assets/pdf/document.pdf".
  final String? assetPath;

  /// Absolute file system path, e.g. from a file picker.
  final String? filePath;

  /// Scroll direction: vertical (default) or horizontal.
  final PdfScrollDirection scrollDirection;

  /// Called when the visible page changes.
  /// Provides the zero-based [currentPage] and [totalPages].
  final void Function(int currentPage, int totalPages)? onPageChanged;

  const PdfViewer({
    super.key,
    this.assetPath,
    this.filePath,
    this.scrollDirection = PdfScrollDirection.vertical,
    this.onPageChanged,
  }) : assert(
         assetPath != null || filePath != null,
         'Either assetPath or filePath must be provided',
       );

  @override
  State<PdfViewer> createState() => _PdfViewerState();
}

class _PdfViewerState extends State<PdfViewer> {
  EventChannel? _eventChannel;
  Stream<dynamic>? _eventStream;

  void _onPlatformViewCreated(int viewId) {
    _eventChannel = EventChannel('pdf_viewer_plugin_view_$viewId/page_events');
    _eventStream = _eventChannel!.receiveBroadcastStream();
    _eventStream!.listen((event) {
      if (event is Map && widget.onPageChanged != null) {
        final currentPage = event['currentPage'] as int? ?? 0;
        final totalPages = event['totalPages'] as int? ?? 0;
        widget.onPageChanged!(currentPage, totalPages);
      }
    });
  }

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
    creationParams['scrollDirection'] =
        widget.scrollDirection == PdfScrollDirection.horizontal
        ? 'horizontal'
        : 'vertical';

    return PlatformViewLink(
      viewType: 'pdf_viewer_plugin_view',
      surfaceFactory: (context, controller) {
        return AndroidViewSurface(
          controller: controller as AndroidViewController,
          gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
          hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        );
      },
      onCreatePlatformView: (params) {
        return PlatformViewsService.initSurfaceAndroidView(
            id: params.id,
            viewType: 'pdf_viewer_plugin_view',
            layoutDirection: TextDirection.ltr,
            creationParams: creationParams,
            creationParamsCodec: const StandardMessageCodec(),
            onFocus: () => params.onFocusChanged(true),
          )
          ..addOnPlatformViewCreatedListener((id) {
            params.onPlatformViewCreated(id);
            _onPlatformViewCreated(id);
          })
          ..create();
      },
    );
  }
}
