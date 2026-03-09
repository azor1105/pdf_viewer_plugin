import 'package:flutter/material.dart';
import 'package:pdf_viewer_plugin/pdf_viewer_plugin.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(home: const PdfViewerExample());
  }
}

class PdfViewerExample extends StatefulWidget {
  const PdfViewerExample({super.key});

  @override
  State<PdfViewerExample> createState() => _PdfViewerExampleState();
}

class _PdfViewerExampleState extends State<PdfViewerExample> {
  PdfScrollDirection _scrollDirection = PdfScrollDirection.vertical;
  int _currentPage = 0;
  int _totalPages = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          _totalPages > 0
              ? 'Page ${_currentPage + 1} / $_totalPages'
              : 'PDF Viewer Example',
        ),
        actions: [
          IconButton(
            icon: Icon(
              _scrollDirection == PdfScrollDirection.vertical
                  ? Icons.swap_horiz
                  : Icons.swap_vert,
            ),
            tooltip: _scrollDirection == PdfScrollDirection.vertical
                ? 'Switch to horizontal'
                : 'Switch to vertical',
            onPressed: () {
              setState(() {
                _scrollDirection =
                    _scrollDirection == PdfScrollDirection.vertical
                    ? PdfScrollDirection.horizontal
                    : PdfScrollDirection.vertical;
                _currentPage = 0;
                _totalPages = 0;
              });
            },
          ),
        ],
      ),
      body: PdfViewer(
        key: ValueKey(_scrollDirection),
        assetPath: 'assets/pdf/sample.pdf',
        scrollDirection: _scrollDirection,
        onPageChanged: (currentPage, totalPages) {
          setState(() {
            _currentPage = currentPage;
            _totalPages = totalPages;
          });
        },
      ),
    );
  }
}
