import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// A widget that displays a PDF document using native Android PDF APIs.
///
/// On API 31+ with SDK extension >= 13, uses the Jetpack PdfViewerFragment
/// with full search, zoom, text selection and annotation support.
/// On older devices, falls back to the framework PdfRenderer.
///
/// Provide either [assetPath] (a Flutter asset) or [filePath] (absolute path).
class PdfViewer extends StatelessWidget {
  /// Path to a Flutter asset, e.g. "assets/pdf/document.pdf".
  final String? assetPath;

  /// Absolute file system path, e.g. from a file picker.
  final String? filePath;

  const PdfViewer({super.key, this.assetPath, this.filePath})
    : assert(
        assetPath != null || filePath != null,
        'Either assetPath or filePath must be provided',
      );

  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid) {
      return const Center(
        child: Text('PDF viewer is only supported on Android'),
      );
    }

    final Map<String, dynamic> creationParams = {};
    if (assetPath != null) {
      creationParams['assetPath'] = assetPath;
    } else if (filePath != null) {
      creationParams['filePath'] = filePath;
    }

    return AndroidView(
      viewType: 'pdf_viewer_plugin_view',
      layoutDirection: TextDirection.ltr,
      creationParams: creationParams,
      creationParamsCodec: const StandardMessageCodec(),
      gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
        Factory<VerticalDragGestureRecognizer>(
          () => VerticalDragGestureRecognizer(),
        ),
        Factory<ScaleGestureRecognizer>(() => ScaleGestureRecognizer()),
        Factory<EagerGestureRecognizer>(() => EagerGestureRecognizer()),
      },
    );
  }
}
// TODO:
// 1. horizontal and vertical scroll support
// 2. handler for changing pages programmatically and that will show the current page number and total page count
// 3. support for text selection and copying to clipboard

// LATER:
// 4. support for searching text in the PDF and highlighting search results
// 5. support for annotations and comments