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
                    return
                }
                guard let document = PDFDocument(url: url) else {
                    DispatchQueue.main.async {
                        self.channel.invokeMethod("onDocumentError",
                                                  arguments: "Failed to open PDF document")
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
        pdf.autoScales          = true
        pdf.maxScaleFactor      = 5.0
        pdf.displaysPageBreaks  = true
        if #available(iOS 16.0, *) {
            pdf.pageShadowsEnabled = true
        }

        applyDisplayMode(to: pdf, isHorizontal: isHorizontal)

        pdf.document = document
        pageCount = document.pageCount

        if initialPage > 0, initialPage < document.pageCount,
           let page = document.page(at: initialPage) {
            pdf.go(to: page)
        }

        // Insert behind the loading overlay so we can fade it out.
        container.insertSubview(pdf, at: 0)
        pdfView = pdf

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handlePageChange),
            name: .PDFViewPageChanged,
            object: pdf
        )

        addScrubber()
        hideLoadingOverlay()
        channel.invokeMethod("onDocumentLoaded", arguments: pageCount)
    }

    /// Configures display mode / direction / page-view-controller on a PDFView.
    private func applyDisplayMode(to pdf: PDFView, isHorizontal: Bool) {
        if isHorizontal {
            pdf.displayMode      = .singlePage
            pdf.displayDirection = .horizontal
            pdf.usePageViewController(true, withViewOptions: nil)
        } else {
            pdf.usePageViewController(false, withViewOptions: nil)
            pdf.displayMode      = .singlePageContinuous
            pdf.displayDirection = .vertical
        }
    }

    // MARK: - Page-change notification

    @objc private func handlePageChange() {
        guard let pdf      = pdfView,
              let page     = pdf.currentPage,
              let document = pdf.document else { return }

        let index = document.index(for: page)
        scrubber?.notifyScrolling()

        guard index != currentPage else { return }
        currentPage = index
        scrubber?.updatePage(index)
        channel.invokeMethod("onPageChanged", arguments: index)
    }

    // MARK: - Page navigation

    private func jumpToPage(_ page: Int) {
        guard let pdf      = pdfView,
              let document = pdf.document,
              page >= 0, page < document.pageCount,
              let pdfPage  = document.page(at: page) else { return }

        if isHorizontal {
            pdf.go(to: pdfPage)
        } else {
            // Scroll to the top of the target page.
            let bounds = pdfPage.bounds(for: pdf.displayBox)
            let dest   = PDFDestination(page: pdfPage,
                                        at: CGPoint(x: 0, y: bounds.height))
            pdf.go(to: dest)
        }
    }

    // MARK: - Runtime direction change

    private func applyScrollDirection(isHorizontal: Bool) {
        self.isHorizontal = isHorizontal
        guard let pdf = pdfView else { return }

        let savedPage = currentPage
        applyDisplayMode(to: pdf, isHorizontal: isHorizontal)

        scrubber?.removeFromSuperview()
        scrubber = nil
        addScrubber()

        // Give the PDF view a frame to settle before jumping.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
            self?.jumpToPage(savedPage)
        }
    }

    // MARK: - Scrubber

    private func addScrubber() {
        let s = PageScrubberView(
            isHorizontal: isHorizontal,
            pageCount: pageCount
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

    init(isHorizontal: Bool, pageCount: Int, onPageSelected: @escaping (Int) -> Void) {
        self.isHorizontal   = isHorizontal
        self.totalPages      = pageCount
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

        if isHorizontal {
            let travel = max(0, sv.bounds.width  - bounds.width  - margin * 2)
            let ratio  = totalPages <= 1 ? 0.0 : CGFloat(currentPage) / CGFloat(totalPages - 1)
            transform  = CGAffineTransform(translationX: ratio * travel, y: 0)
        } else {
            let travel = max(0, sv.bounds.height - bounds.height - margin * 2)
            let ratio  = totalPages <= 1 ? 0.0 : CGFloat(currentPage) / CGFloat(totalPages - 1)
            transform  = CGAffineTransform(translationX: 0, y: ratio * travel)
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        repositionAlongAxis()
        setNeedsDisplay()
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
