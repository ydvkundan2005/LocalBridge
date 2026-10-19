// swift-tools-version: 6.2
// LocalBridge macOS — Package.swift
// Pinned exactly to 01_TECH_STACK.md: SwiftNIO 2.104.0, swift-nio-ssl 2.37.2
// macOS deployment target: 13.0 minimum (subject to actual M1 macOS version)
import PackageDescription

let package = Package(
    name: "LocalBridgeMac",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .executable(
            name: "LocalBridgeMac",
            targets: ["LocalBridgeMac"]
        ),
    ],
    dependencies: [
        // SwiftNIO — exact pin required
        .package(
            url: "https://github.com/apple/swift-nio.git",
            exact: "2.104.0"
        ),
        // SwiftNIO SSL — exact pin required; 2.37.2 is security floor (01_TECH_STACK.md)
        .package(
            url: "https://github.com/apple/swift-nio-ssl.git",
            exact: "2.37.2"
        ),
    ],
    targets: [
        .executableTarget(
            name: "LocalBridgeMac",
            dependencies: [
                .product(name: "NIO", package: "swift-nio"),
                .product(name: "NIOCore", package: "swift-nio"),
                .product(name: "NIOPosix", package: "swift-nio"),
                .product(name: "NIOHTTP1", package: "swift-nio"),
                .product(name: "NIOWebSocket", package: "swift-nio"),
                .product(name: "NIOSSL", package: "swift-nio-ssl"),
            ],
            path: "Sources/LocalBridgeMac",
            swiftSettings: [
                .swiftLanguageMode(.v6),
            ]
        ),
    ]
)
