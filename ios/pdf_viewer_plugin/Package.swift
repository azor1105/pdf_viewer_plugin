// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "pdf_viewer_plugin",
    platforms: [
        .iOS("13.0"),
    ],
    products: [
        .library(name: "pdf-viewer-plugin", targets: ["pdf_viewer_plugin"]),
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework"),
    ],
    targets: [
        .target(
            name: "pdf_viewer_plugin",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework")
            ]
        ),
    ]
)
