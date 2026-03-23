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

class PdfViewerPage extends StatelessWidget {
  const PdfViewerPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('PDF Viewer Example')),
      body: SafeArea(
        child: PdfViewer.asset(
          'assets/pdf/sample.pdf',
          scrollDirection: ScrollDirection.vertical,
          onPageChanged: (value) {
            debugPrint('Page changed: $value');
          },
          onDocumentLoaded: () {
            debugPrint('Document loaded');
          },
        ),
      ),
    );
  }
}
