import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'pdf_viewer_plugin_platform_interface.dart';

/// An implementation of [PdfViewerPluginPlatform] that uses method channels.
class MethodChannelPdfViewerPlugin extends PdfViewerPluginPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('pdf_viewer_plugin');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
