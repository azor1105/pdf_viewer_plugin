import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'pdf_viewer_plugin_method_channel.dart';

abstract class PdfViewerPluginPlatform extends PlatformInterface {
  /// Constructs a PdfViewerPluginPlatform.
  PdfViewerPluginPlatform() : super(token: _token);

  static final Object _token = Object();

  static PdfViewerPluginPlatform _instance = MethodChannelPdfViewerPlugin();

  /// The default instance of [PdfViewerPluginPlatform] to use.
  ///
  /// Defaults to [MethodChannelPdfViewerPlugin].
  static PdfViewerPluginPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [PdfViewerPluginPlatform] when
  /// they register themselves.
  static set instance(PdfViewerPluginPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
