import 'package:flutter/material.dart';
import 'package:pdf_viewer_plugin/pdf_viewer_plugin.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('PDF Viewer Example')),
        body: SafeArea(
          child: PdfViewer(
            assetPath: 'assets/pdf/sample.pdf',
            scrollDirection: ScrollDirection.horizontal,
            initialPage: 100,
            onPageChanged: (value){
              debugPrint('Page changed: $value');
            },
          ),
        ),
      ),
    );
  }
}
