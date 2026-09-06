import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:pdf_viewer_plugin/pdf_viewer_plugin.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(home: const HomePage());
  }
}

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      floatingActionButton: FloatingActionButton(
        onPressed: () {
          Navigator.of(context).push(
            MaterialPageRoute(builder: (context) => const PdfViewerPage()),
          );
        },
        child: Icon(Icons.book),
      ),
    );
  }
}

class PdfViewerPage extends StatefulWidget {
  const PdfViewerPage({super.key});

  @override
  State<PdfViewerPage> createState() => _PdfViewerPageState();
}

class _PdfViewerPageState extends State<PdfViewerPage> {
  final PdfController _controller = PdfController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _snapshot() async {
    final page = _controller.currentPage;
    final stopwatch = Stopwatch()..start();
    final bytes = await _controller.renderPage(page, width: 800);
    stopwatch.stop();
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (_) => _SnapshotDialog(
        page: page,
        pageCount: _controller.pageCount,
        bytes: bytes,
        elapsedMs: stopwatch.elapsedMilliseconds,
      ),
    );
  }

  /// Fires 10 snapshots at once to prove they queue behind the viewer's own
  /// rendering instead of racing it — the single-open-page case on Android.
  Future<void> _stressTest() async {
    final messenger = ScaffoldMessenger.of(context);
    final stopwatch = Stopwatch()..start();
    final results = await Future.wait(
      List.generate(10, (i) => _controller.renderPage(i, width: 800)),
    );
    stopwatch.stop();
    final ok = results.where((b) => b != null && b.isNotEmpty).length;
    messenger.showSnackBar(
      SnackBar(
        content: Text('$ok/10 rendered in ${stopwatch.elapsedMilliseconds}ms'),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('PDF Viewer Example'),
        actions: [
          IconButton(
            onPressed: _stressTest,
            icon: const Icon(Icons.speed),
            tooltip: '10 concurrent renders',
          ),
          IconButton(
            onPressed: _snapshot,
            icon: const Icon(Icons.camera_alt),
            tooltip: 'Snapshot current page',
          ),
        ],
      ),
      body: SafeArea(
        child: PdfViewer.asset(
          'assets/pdf/sample.pdf',
          controller: _controller,
          scrollDirection: ScrollDirection.vertical,
          onPageChanged: (value) {
            debugPrint('Page changed: $value');
          },
          onDocumentLoaded: () {
            debugPrint('Document loaded, pageCount=${_controller.pageCount}');
          },
        ),
      ),
    );
  }
}

class _SnapshotDialog extends StatelessWidget {
  const _SnapshotDialog({
    required this.page,
    required this.pageCount,
    required this.bytes,
    required this.elapsedMs,
  });

  final int page;
  final int pageCount;
  final Uint8List? bytes;
  final int elapsedMs;

  Future<ui.Image> _decode(Uint8List data) async {
    final codec = await ui.instantiateImageCodec(data);
    final frame = await codec.getNextFrame();
    return frame.image;
  }

  @override
  Widget build(BuildContext context) {
    final data = bytes;
    return AlertDialog(
      title: Text('Page ${page + 1} of $pageCount'),
      content: data == null
          ? const Text('renderPage returned null')
          : SizedBox(
              width: 300,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  FutureBuilder<ui.Image>(
                    future: _decode(data),
                    builder: (context, snap) {
                      final img = snap.data;
                      return Text(
                        '${(data.lengthInBytes / 1024).toStringAsFixed(1)} KB '
                        '· ${elapsedMs}ms'
                        '${img == null ? '' : ' · ${img.width}×${img.height}px'}',
                        style: const TextStyle(fontFamily: 'monospace'),
                      );
                    },
                  ),
                  const SizedBox(height: 12),
                  Flexible(child: Image.memory(data)),
                ],
              ),
            ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Close'),
        ),
      ],
    );
  }
}
