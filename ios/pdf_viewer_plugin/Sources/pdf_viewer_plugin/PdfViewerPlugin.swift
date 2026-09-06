import Flutter
import UIKit
import PDFKit

// MARK: - Plugin registration

public class PdfViewerPlugin: NSObject, FlutterPlugin {

    public static func register(with registrar: FlutterPluginRegistrar) {
        let factory = PdfViewFactory(
            messenger: registrar.messenger(),
            registrar: registrar
        )
        registrar.register(factory, withId: "pdf_viewer_plugin_view")
    }
}

// MARK: - Platform-view factory

class PdfViewFactory: NSObject, FlutterPlatformViewFactory {

    private let messenger: FlutterBinaryMessenger
    private let registrar: FlutterPluginRegistrar

    init(messenger: FlutterBinaryMessenger, registrar: FlutterPluginRegistrar) {
        self.messenger = messenger
        self.registrar = registrar
        super.init()
    }

    func create(
        withFrame frame: CGRect,
        viewIdentifier viewId: Int64,
        arguments args: Any?
    ) -> FlutterPlatformView {
        return PdfPlatformView(
            frame: frame,
            viewId: viewId,
            args: args,
            messenger: messenger,
            registrar: registrar
        )
    }

    func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        return FlutterStandardMessageCodec.sharedInstance()
    }
}

// MARK: - Platform view

class PdfPlatformView: NSObject, FlutterPlatformView {

    private let container: UIView
    private let channel: FlutterMethodChannel
    private let registrar: FlutterPluginRegistrar

    private var pdfView: PDFView?
    private var loadingView: UIView?
    private var scrubber: PageScrubberView?

    private var pageCount: Int = 0
    private var currentPage: Int = 0
    private var isHorizontal: Bool = false
    private var scrollObserver: NSKeyValueObservation?

    /// Serial so two snapshot requests never render into PDFKit concurrently.
    private static let snapshotQueue = DispatchQueue(
        label: "pdf_viewer_plugin.snapshot",
        qos: .userInitiated
    )
    private static let minSnapshotWidth = 200
    private static let maxSnapshotWidth = 2000
    private static let snapshotJpegQuality: CGFloat = 0.8

    init(
        frame: CGRect,
        viewId: Int64,
        args: Any?,
        messenger: FlutterBinaryMessenger,
        registrar: FlutterPluginRegistrar
    ) {
        container = UIView(frame: frame)
        container.backgroundColor = .white
        channel = FlutterMethodChannel(
            name: "pdf_viewer_plugin_view/\(viewId)",
            binaryMessenger: messenger
        )
        self.registrar = registrar
        super.init()

        let params = args as? [String: Any]
        isHorizontal = (params?["scrollDirection"] as? String) == "horizontal"
        let initialPage = (params?["initialPage"] as? Int) ?? 0
        currentPage = initialPage

        showLoadingOverlay()
        setupMethodChannel()

        let assetPath = params?["assetPath"] as? String
        let filePath  = params?["filePath"]  as? String

        loadDocument(assetPath: assetPath, filePath: filePath, initialPage: initialPage)
    }

    // MARK: FlutterPlatformView

    func view() -> UIView { return container }

    deinit {
        NotificationCenter.default.removeObserver(self)
        stopScrollObservation()
        channel.setMethodCallHandler(nil)
    }

    // MARK: - Method channel

    private func setupMethodChannel() {
        channel.setMethodCallHandler { [weak self] call, result in
            guard let self = self else { return }
            switch call.method {
            case "setScrollDirection":
                let isHor = (call.arguments as? String) == "horizontal"
                if isHor != self.isHorizontal {
                    self.applyScrollDirection(isHorizontal: isHor)
                }
                result(nil)
            case "renderPage":
                let args = call.arguments as? [String: Any]
                let page = (args?["page"] as? Int) ?? 0
                let width = (args?["width"] as? Int) ?? 800
                self.renderPage(at: page, targetWidth: width, result: result)
            case "jumpToPage":
                if let page = call.arguments as? Int {
                    self.jumpToPage(page)
                }
                result(nil)
            default:
                result(FlutterMethodNotImplemented)
            }
        }
    }

    // MARK: - Document loading

    private func loadDocument(assetPath: String?, filePath: String?, initialPage: Int) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            do {
                let url: URL
                if let assetPath = assetPath {
                    url = try self.resolveAssetURL(assetPath: assetPath)
                } else if let filePath = filePath {
                    url = URL(fileURLWithPath: filePath)
                } else {
                    DispatchQueue.main.async {
                        self.channel.invokeMethod("onDocumentError",
                                                  arguments: "No file path or asset path provided")
                    }
                    return
                }
                // First attempt: open the path as-is.
                // Second attempt: if that fails AND the path contains a
                // sandbox UUID (common after an iOS reinstall or update
                // that rotates the app-container UUID), try to remap the
                // relative portion under the *current* sandbox directories.
                var document = PDFDocument(url: url)
                if document == nil, let filePath = filePath,
                   let remapped = self.remapSandboxPath(filePath) {
                    document = PDFDocument(url: remapped)
                }
                guard let document = document else {
                    DispatchQueue.main.async {
                        self.channel.invokeMethod("onDocumentError",
                                                  arguments: "Failed to open PDF document – file not found at the given path. On iOS the app-sandbox path changes on every reinstall; store only the filename and resolve it at runtime via the app's Documents directory.")
                    }
                    return
                }
                DispatchQueue.main.async {
                    self.setupPdfView(document: document, initialPage: initialPage)
                }
            } catch {
                DispatchQueue.main.async {
                    self.channel.invokeMethod("onDocumentError",
                                             arguments: error.localizedDescription)
                }
            }
        }
    }

    /// Attempts to remap an iOS sandbox file path whose container UUID has
    /// become stale (e.g. after an app reinstall or update).
    ///
    /// iOS path structure:
    ///   `.../Containers/Data/Application/<UUID>/Documents/file.pdf`
    ///
    /// This method extracts the well-known subdirectory and filename that
    /// follow the UUID, then resolves them relative to the *current* sandbox
    /// container so the file can still be opened.
    ///
    /// Returns `nil` when the path is not a recognisable iOS sandbox path or
    /// when the remapped file does not exist on disk.
    private func remapSandboxPath(_ originalPath: String) -> URL? {
        // Match the UUID segment that iOS inserts for every app container.
        guard let regex = try? NSRegularExpression(
            pattern: #"/Containers/Data/Application/[0-9A-Fa-f\-]+/(.+)"#
        ) else { return nil }

        let ns = originalPath as NSString
        guard let match = regex.firstMatch(
            in: originalPath,
            range: NSRange(location: 0, length: ns.length)
        ) else { return nil }

        // `relative` is everything after the UUID, e.g. "Documents/file.pdf"
        let relativeRange = match.range(at: 1)
        guard relativeRange.location != NSNotFound else { return nil }
        let relative = ns.substring(with: relativeRange)

        let fm = FileManager.default

        // Map the leading well-known directory name to a FileManager search
        // path so we always resolve against the *live* container.
        let mapping: [(String, FileManager.SearchPathDirectory)] = [
            ("Documents/", .documentDirectory),
            ("Library/Caches/", .cachesDirectory),
            ("Library/",  .libraryDirectory),
        ]
        for (prefix, searchDir) in mapping {
            guard relative.hasPrefix(prefix) else { continue }
            let suffix = String(relative.dropFirst(prefix.count))
            guard let base = fm.urls(for: searchDir, in: .userDomainMask).first
            else { continue }
            let candidate = base.appendingPathComponent(suffix)
            if fm.fileExists(atPath: candidate.path) { return candidate }
        }

        // Handle the tmp/ directory separately (not a search-path directory).
        if relative.hasPrefix("tmp/") {
            let suffix = String(relative.dropFirst(4))
            let candidate = URL(fileURLWithPath: NSTemporaryDirectory())
                .appendingPathComponent(suffix)
            if fm.fileExists(atPath: candidate.path) { return candidate }
        }

        return nil
    }

    /// Resolves a Flutter asset path to a file URL in the app bundle.
    private func resolveAssetURL(assetPath: String) throws -> URL {
        let key = registrar.lookupKey(forAsset: assetPath)
        guard let url = Bundle.main.url(forResource: key, withExtension: nil) else {
            throw NSError(
                domain: "PdfViewerPlugin",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Asset not found: \(assetPath)"]
            )
        }
        return url
    }

    // MARK: - Loading overlay

    private func showLoadingOverlay() {
        let overlay = UIView(frame: container.bounds)
        overlay.backgroundColor = .white
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]

        let indicator = UIActivityIndicatorView(style: .large)
        indicator.center = CGPoint(x: overlay.bounds.midX, y: overlay.bounds.midY)
        indicator.autoresizingMask = [
            .flexibleLeftMargin, .flexibleRightMargin,
            .flexibleTopMargin,  .flexibleBottomMargin,
        ]
        indicator.startAnimating()

        overlay.addSubview(indicator)
        container.addSubview(overlay)
        loadingView = overlay
    }

    private func hideLoadingOverlay() {
        UIView.animate(withDuration: 0.2) {
            self.loadingView?.alpha = 0
        } completion: { _ in
            self.loadingView?.removeFromSuperview()
            self.loadingView = nil
        }
    }

    // MARK: - PDFView setup

    private func setupPdfView(document: PDFDocument, initialPage: Int) {
        let pdf = PDFView(frame: container.bounds)
        pdf.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // Keep the max zoom reasonable – a higher cap forces PDFKit to
        // maintain proportionally larger tile caches in GPU memory, which
        // causes sustained thermal load with large documents.
        pdf.maxScaleFactor   = 3.0
        // Page breaks and shadows add extra GPU composite passes on every
        // frame; skip them in single-page horizontal mode where they have
        // no visual effect anyway.
        pdf.displaysPageBreaks = !isHorizontal
        if #available(iOS 16.0, *) {
            pdf.pageShadowsEnabled = false
        }

        pdf.backgroundColor = .white
        pdf.documentView?.backgroundColor = .white

        applyDisplayMode(to: pdf, isHorizontal: isHorizontal)

        pdf.document = document
        pageCount = document.pageCount

        // Insert behind the loading overlay so we can fade it out.
        container.insertSubview(pdf, at: 0)

        // Hide the native scroll indicators (grabber bar).
        hideScrollIndicators(in: pdf)
        pdfView = pdf

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handlePageChange),
            name: .PDFViewPageChanged,
            object: pdf
        )

        addScrubber()

        // Force a synchronous layout pass so pdf.bounds is valid before we
        // compute the fit-to-width scale, then jump to the initial page.
        DispatchQueue.main.async { [weak self, weak pdf] in
            guard let self = self, let pdf = pdf else { return }
            // layoutIfNeeded gives the PDFView its real frame before we read
            // pdf.bounds.width — without this the bounds can still be zero.
            self.container.layoutIfNeeded()
            if !self.isHorizontal {
                self.fitToWidth(pdf)
            }
            if initialPage > 0 {
                // One more run-loop turn lets the scale change commit its
                // internal content-size recalculation before we scroll.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
                    self?.jumpToPage(initialPage)
                    // After a brief settle, force-sync the scrubber to the
                    // actual page that PDFKit landed on (handles cases where
                    // the notif-based updatePage guard short-circuits).
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
                        self?.syncScrubberToCurrentPage()
                        self?.startScrollObservation()
                    }
                }
            } else {
                self.startScrollObservation()
            }
        }

        hideLoadingOverlay()
        channel.invokeMethod("onDocumentLoaded", arguments: pageCount)
    }

    /// Configures display mode / direction / page-view-controller on a PDFView.
    // MARK: - Page snapshot

    /// Renders the page at [index] to a JPEG [targetWidth] points wide and
    /// replies with its bytes, or nil when it cannot be rendered.
    ///
    /// Used to snapshot the page the reader is on for use outside the viewer.
    /// The render runs on a serial background queue so a large page does not
    /// stall the frame the user is looking at.
    private func renderPage(at index: Int, targetWidth: Int, result: @escaping FlutterResult) {
        guard let document = pdfView?.document,
              index >= 0,
              index < document.pageCount,
              let page = document.page(at: index)
        else {
            result(nil)
            return
        }

        let box = PDFDisplayBox.mediaBox
        let pageRect = page.bounds(for: box)
        // bounds(for:) is in unrotated page space, so a page with 90°/270°
        // rotation renders sideways unless the axes are swapped here.
        let quarterTurns = (page.rotation / 90) % 2
        let contentSize = quarterTurns == 0
            ? pageRect.size
            : CGSize(width: pageRect.height, height: pageRect.width)

        guard contentSize.width > 0, contentSize.height > 0 else {
            result(nil)
            return
        }

        let width = CGFloat(min(max(targetWidth, Self.minSnapshotWidth), Self.maxSnapshotWidth))
        let scale = width / contentSize.width
        let outputSize = CGSize(
            width: width,
            height: max((contentSize.height * scale).rounded(), 1)
        )

        Self.snapshotQueue.async {
            let format = UIGraphicsImageRendererFormat()
            // Without this the renderer multiplies by the screen scale, so an
            // 800pt request comes back 2400px wide on a 3x device.
            format.scale = 1
            format.opaque = true

            let image = UIGraphicsImageRenderer(size: outputSize, format: format).image { ctx in
                // PDF pages draw with a transparent background and JPEG has no
                // alpha channel, so fill white first.
                UIColor.white.setFill()
                ctx.fill(CGRect(origin: .zero, size: outputSize))

                let cg = ctx.cgContext
                // PDF coordinates are y-up; UIKit's are y-down.
                cg.translateBy(x: 0, y: outputSize.height)
                cg.scaleBy(x: scale, y: -scale)
                page.draw(with: box, to: cg)
            }

            let data = image.jpegData(compressionQuality: Self.snapshotJpegQuality)
            DispatchQueue.main.async {
                result(data.map { FlutterStandardTypedData(bytes: $0) })
            }
        }
    }

    private func applyDisplayMode(to pdf: PDFView, isHorizontal: Bool) {
        if isHorizontal {
            pdf.displayMode      = .singlePage
            pdf.displayDirection = .horizontal
            pdf.autoScales       = true
            pdf.usePageViewController(true, withViewOptions: nil)
        } else {
            pdf.usePageViewController(false, withViewOptions: nil)
            pdf.displayMode      = .singlePageContinuous
            pdf.displayDirection = .vertical
            // autoScales is unreliable in singlePageContinuous mode before the
            // view has valid bounds. Scale is applied manually via fitToWidth.
            pdf.autoScales       = false
        }
    }

    // MARK: - Page-change notification

    @objc private func handlePageChange() {
        refreshCurrentPage()
    }

    // MARK: - Page navigation

    private func jumpToPage(_ page: Int) {
        guard let pdf      = pdfView,
              let document = pdf.document,
              page >= 0, page < document.pageCount,
              let pdfPage  = document.page(at: page) else { return }
        // pdf.go(to:PDFPage) is reliable for both horizontal paged mode and
        // vertical continuous mode — it scrolls to make the page visible
        // without needing PDFDestination coordinate arithmetic.
        pdf.go(to: pdfPage)
    }

    // MARK: - Runtime direction change

    private func applyScrollDirection(isHorizontal: Bool) {
        self.isHorizontal = isHorizontal
        guard let pdf = pdfView else { return }

        let savedPage = currentPage
        stopScrollObservation()
        applyDisplayMode(to: pdf, isHorizontal: isHorizontal)
        pdf.backgroundColor = .white
        pdf.documentView?.backgroundColor = .white
        hideScrollIndicators(in: pdf)

        scrubber?.removeFromSuperview()
        scrubber = nil
        addScrubber()

        DispatchQueue.main.async { [weak self, weak pdf] in
            guard let self = self, let pdf = pdf else { return }
            self.container.layoutIfNeeded()
            if !self.isHorizontal {
                self.fitToWidth(pdf)
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
                self?.jumpToPage(savedPage)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
                    self?.syncScrubberToCurrentPage()
                    self?.startScrollObservation()
                }
            }
        }
    }

    // MARK: - Scale helpers

    /// Scales vertical-continuous mode so pages fill the view width exactly.
    private func fitToWidth(_ pdf: PDFView) {
        guard let page = pdf.document?.page(at: 0) else { return }
        let pageWidth = page.bounds(for: pdf.displayBox).width
        // Use the first non-zero width available. The Flutter engine may have
        // not yet assigned the final frame when this is first called.
        let viewWidth: CGFloat
        if pdf.bounds.width > 0 {
            viewWidth = pdf.bounds.width
        } else if container.bounds.width > 0 {
            viewWidth = container.bounds.width
        } else {
            viewWidth = UIScreen.main.bounds.width
        }
        guard pageWidth > 0 else { return }
        let scale = viewWidth / pageWidth
        pdf.minScaleFactor = scale * 0.5   // allow zooming out to half width
        pdf.scaleFactor    = scale
    }

    // MARK: - Scroll observation (vertical mode only)

    /// Observes the PDFView's internal UIScrollView so the scrubber updates
    /// in real time while the user drags, rather than only on the discrete
    /// PDFViewPageChanged notification.
    private func startScrollObservation() {
        guard !isHorizontal, let pdf = pdfView else { return }
        let sv = findScrollView(in: pdf)
        scrollObserver = sv?.observe(\.contentOffset, options: .new) { [weak self] _, _ in
            DispatchQueue.main.async { self?.refreshCurrentPage() }
        }
    }

    private func stopScrollObservation() {
        scrollObserver?.invalidate()
        scrollObserver = nil
    }

    /// Reads the current page from the PDFView and, if it changed, updates
    /// the scrubber and fires the Flutter callback.
    private func refreshCurrentPage() {
        guard let pdf      = pdfView,
              let page     = pdf.currentPage,
              let document = pdf.document else { return }
        let index = document.index(for: page)
        // notifyScrolling triggers a Core Graphics redraw; only call it
        // in vertical mode where it reflects real continuous scrolling.
        // In horizontal paged mode discrete page-change events drive the
        // scrubber through updatePage instead.
        if !isHorizontal { scrubber?.notifyScrolling() }
        guard index != currentPage else { return }
        currentPage = index
        scrubber?.updatePage(index)
        channel.invokeMethod("onPageChanged", arguments: index)
    }

    /// Finds the first UIScrollView in a PDFView's view hierarchy.
    private func findScrollView(in view: UIView) -> UIScrollView? {
        if let sv = view as? UIScrollView { return sv }
        for sub in view.subviews {
            if let found = findScrollView(in: sub) { return found }
        }
        return nil
    }

    /// Hides all scroll indicators (the native grabber bar) inside a PDFView.
    private func hideScrollIndicators(in view: UIView) {
        if let sv = view as? UIScrollView {
            sv.showsVerticalScrollIndicator   = false
            sv.showsHorizontalScrollIndicator = false
        }
        for sub in view.subviews {
            hideScrollIndicators(in: sub)
        }
    }

    /// Reads the true current page from PDFKit and forces the scrubber to
    /// that position regardless of its cached `currentPage`. This is called
    /// after an initial/direction-change jump to guarantee the handle is in
    /// the right place even when `PDFViewPageChanged` didn't fire or the
    /// superview had zero bounds during earlier `repositionAlongAxis` calls.
    private func syncScrubberToCurrentPage() {
        guard let pdf      = pdfView,
              let page     = pdf.currentPage,
              let document = pdf.document else { return }
        let idx = document.index(for: page)
        currentPage = idx
        scrubber?.forceSetPage(idx)
    }

    // MARK: - Scrubber

    private func addScrubber() {
        let s = PageScrubberView(
            isHorizontal: isHorizontal,
            pageCount: pageCount,
            initialPage: currentPage
        ) { [weak self] page in
            self?.jumpToPage(page)
        }
        s.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(s)

        if isHorizontal {
            // 150 × 100 pt, bottom-left – mirrors Android scrubber layout.
            NSLayoutConstraint.activate([
                s.leadingAnchor .constraint(equalTo: container.leadingAnchor,  constant:  8),
                s.bottomAnchor  .constraint(equalTo: container.bottomAnchor,   constant: -8),
                s.widthAnchor   .constraint(equalToConstant: 150),
                s.heightAnchor  .constraint(equalToConstant: 100),
            ])
        } else {
            // 150 × 50 pt, top-right – mirrors Android scrubber layout.
            NSLayoutConstraint.activate([
                s.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -8),
                s.topAnchor     .constraint(equalTo: container.topAnchor,      constant:  8),
                s.widthAnchor   .constraint(equalToConstant: 150),
                s.heightAnchor  .constraint(equalToConstant: 50),
            ])
        }
        scrubber = s
    }
}

// MARK: - PageScrubberView

/// A draggable scrubber handle that mirrors the Android `PageScrubberView`.
///
/// • **Horizontal mode** – 150 × 100 pt view, circle bottom-centred,
///   pill label drawn above the circle; drag left/right to change pages.
/// • **Vertical mode**   – 150 × 50 pt view, circle right-centred,
///   pill label drawn to the left; drag up/down to change pages.
///
/// As the current page changes the view repositions itself along its axis so
/// the handle always reflects the reading position.
class PageScrubberView: UIView {

    private let isHorizontal: Bool
    private let totalPages: Int
    private let onPageSelected: (Int) -> Void

    private var currentPage: Int = 0
    private var isDragging: Bool  = false
    private var showLabel: Bool   = false
    private var hideTimer: Timer?

    init(isHorizontal: Bool, pageCount: Int, initialPage: Int = 0, onPageSelected: @escaping (Int) -> Void) {
        self.isHorizontal   = isHorizontal
        self.totalPages      = pageCount
        self.currentPage     = initialPage
        self.onPageSelected  = onPageSelected
        super.init(frame: .zero)
        backgroundColor        = .clear
        isUserInteractionEnabled = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) not supported") }

    // MARK: - Public API

    func updatePage(_ page: Int) {
        guard page != currentPage else { return }
        currentPage = page
        repositionAlongAxis()
        setNeedsDisplay()
    }

    func notifyScrolling() {
        showLabelBriefly()
    }

    // MARK: - Geometry helpers

    private var circleRadius: CGFloat { 25 }

    /// X of the circle centre inside the scrubber's own coordinate system.
    private var circleCX: CGFloat {
        isHorizontal ? bounds.width / 2 : bounds.width - circleRadius
    }

    /// Y of the circle centre inside the scrubber's own coordinate system.
    private var circleCY: CGFloat {
        isHorizontal ? bounds.height - circleRadius : bounds.height / 2
    }

    // MARK: - Auto-positioning

    /// Offsets the scrubber along its primary axis so its position reflects
    /// the current page fraction within the document.
    private func repositionAlongAxis() {
        guard let sv = superview else { return }
        let margin: CGFloat = 8

        // If the superview hasn't received its final frame from the Flutter
        // engine yet (bounds == .zero), fall back to the screen bounds so the
        // initial position is still painted correctly.
        let svWidth  = sv.bounds.width  > 0 ? sv.bounds.width  : UIScreen.main.bounds.width
        let svHeight = sv.bounds.height > 0 ? sv.bounds.height : UIScreen.main.bounds.height

        if isHorizontal {
            let travel = max(0, svWidth  - bounds.width  - margin * 2)
            let ratio  = totalPages <= 1 ? 0.0 : CGFloat(currentPage) / CGFloat(totalPages - 1)
            transform  = CGAffineTransform(translationX: ratio * travel, y: 0)
        } else {
            let travel = max(0, svHeight - bounds.height - margin * 2)
            let ratio  = totalPages <= 1 ? 0.0 : CGFloat(currentPage) / CGFloat(totalPages - 1)
            transform  = CGAffineTransform(translationX: 0, y: ratio * travel)
        }
    }

    /// Force-updates the displayed page and repositions the scrubber handle.
    /// Unlike `updatePage(_:)`, this bypasses the same-page guard — use it
    /// when the view bounds have just become valid after a layout pass.
    func forceSetPage(_ page: Int) {
        currentPage = page
        repositionAlongAxis()
        setNeedsDisplay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // repositionAlongAxis only mutates `transform`; no content pixels
        // change, so we do NOT call setNeedsDisplay() here. Calling it on
        // every layout pass causes an unnecessary Core Graphics redraw during
        // UIPageViewController transitions, contributing to thermal load.
        repositionAlongAxis()
    }

    // MARK: - Label visibility

    private func showLabelBriefly() {
        showLabel = true
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: false) { [weak self] _ in
            self?.showLabel = false
            self?.setNeedsDisplay()
        }
        setNeedsDisplay()
    }

    // MARK: - Drawing

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }

        let cx = circleCX
        let cy = circleCY
        let r  = circleRadius

        // ── Label pill ──────────────────────────────────────────────────────
        if isDragging || showLabel {
            let label = "\(currentPage + 1) / \(totalPages)"
            let attrs: [NSAttributedString.Key: Any] = [
                .font:            UIFont.boldSystemFont(ofSize: 12),
                .foregroundColor: UIColor.white,
            ]
            let textSize = (label as NSString).size(withAttributes: attrs)
            let padH: CGFloat = 14
            let padV: CGFloat = 10
            let pillW = textSize.width  + padH * 2
            let pillH = textSize.height + padV * 2

            ctx.setFillColor(UIColor(white: 0.118, alpha: 0.82).cgColor)

            if isHorizontal {
                // Pill drawn above the circle.
                let pillBottom = cy - r - 10
                let pillTop    = pillBottom - pillH
                let pillLeft   = cx - pillW / 2
                let pillRect   = CGRect(x: pillLeft, y: pillTop, width: pillW, height: pillH)
                let pillPath   = UIBezierPath(roundedRect: pillRect, cornerRadius: pillH / 2)
                ctx.addPath(pillPath.cgPath); ctx.fillPath()
                (label as NSString).draw(at: CGPoint(x: pillLeft + padH, y: pillTop + padV),
                                         withAttributes: attrs)
            } else {
                // Pill drawn to the left of the circle.
                let pillRight = cx - r - 10
                let pillLeft  = pillRight - pillW
                let pillTop   = cy - pillH / 2
                let pillRect  = CGRect(x: pillLeft, y: pillTop, width: pillW, height: pillH)
                let pillPath  = UIBezierPath(roundedRect: pillRect, cornerRadius: pillH / 2)
                ctx.addPath(pillPath.cgPath); ctx.fillPath()
                (label as NSString).draw(at: CGPoint(x: pillLeft + padH, y: pillTop + padV),
                                         withAttributes: attrs)
            }
        }

        // ── Circle background ────────────────────────────────────────────────
        ctx.setFillColor(UIColor(red: 0.118, green: 0.118, blue: 0.118, alpha: 0.824).cgColor)
        ctx.fillEllipse(in: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))

        // ── Direction arrows ─────────────────────────────────────────────────
        ctx.setStrokeColor(UIColor.white.cgColor)
        ctx.setLineWidth(2.5)
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)

        let aw: CGFloat = 10
        if isHorizontal {
            // Left chevron
            let lx = cx - 8
            ctx.move(to: CGPoint(x: lx + 6, y: cy - aw / 2))
            ctx.addLine(to: CGPoint(x: lx,     y: cy))
            ctx.addLine(to: CGPoint(x: lx + 6, y: cy + aw / 2))
            ctx.strokePath()
            // Right chevron
            let rx = cx + 8
            ctx.move(to: CGPoint(x: rx - 6, y: cy - aw / 2))
            ctx.addLine(to: CGPoint(x: rx,     y: cy))
            ctx.addLine(to: CGPoint(x: rx - 6, y: cy + aw / 2))
            ctx.strokePath()
        } else {
            // Up chevron
            let uy = cy - 8
            ctx.move(to: CGPoint(x: cx - aw / 2, y: uy + 6))
            ctx.addLine(to: CGPoint(x: cx,         y: uy))
            ctx.addLine(to: CGPoint(x: cx + aw / 2, y: uy + 6))
            ctx.strokePath()
            // Down chevron
            let dy = cy + 8
            ctx.move(to: CGPoint(x: cx - aw / 2, y: dy - 6))
            ctx.addLine(to: CGPoint(x: cx,         y: dy))
            ctx.addLine(to: CGPoint(x: cx + aw / 2, y: dy - 6))
            ctx.strokePath()
        }
    }

    // MARK: - Touch handling

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let pt = touches.first?.location(in: self) else { return }
        let dx = pt.x - circleCX
        let dy = pt.y - circleCY
        guard dx * dx + dy * dy <= circleRadius * circleRadius else { return }

        isDragging = true
        showLabel  = true
        hideTimer?.invalidate()
        setNeedsDisplay()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard isDragging,
              let touch = touches.first,
              let sv    = superview else { return }

        let pt = touch.location(in: sv)

        let targetPage: Int
        if isHorizontal {
            let fraction = clamp(pt.x / sv.bounds.width, lo: 0, hi: 1)
            targetPage   = clamp(Int(fraction * CGFloat(totalPages - 1)), lo: 0, hi: totalPages - 1)
        } else {
            let fraction = clamp(pt.y / sv.bounds.height, lo: 0, hi: 1)
            targetPage   = clamp(Int(fraction * CGFloat(totalPages - 1)), lo: 0, hi: totalPages - 1)
        }

        guard targetPage != currentPage else { return }
        currentPage = targetPage
        repositionAlongAxis()
        setNeedsDisplay()
        onPageSelected(targetPage)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        isDragging = false
        showLabelBriefly()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        isDragging = false
        showLabelBriefly()
    }

    // MARK: - Helpers

    private func clamp<T: Comparable>(_ value: T, lo: T, hi: T) -> T {
        return min(max(value, lo), hi)
    }
}
