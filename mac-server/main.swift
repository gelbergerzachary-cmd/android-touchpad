// TouchpadServer — receives gestures from the Android Touchpad app over UDP
// and turns them into native macOS pointer, scroll, zoom and keyboard events.
import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

let port: UInt16 = 50505

// MARK: - Helpers

func field(_ raw: UInt32) -> CGEventField { unsafeBitCast(raw, to: CGEventField.self) }

func now() -> Double { Double(DispatchTime.now().uptimeNanoseconds) / 1e9 }

// IOHIDEventPhaseBits / CGScrollPhase values
enum Phase { static let began: Int64 = 1, changed: Int64 = 2, ended: Int64 = 4 }
enum Momentum { static let none: Int64 = 0, begin: Int64 = 1, cont: Int64 = 2, end: Int64 = 3 }

func loadPin(regenerate: Bool) -> String {
    let dir = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Application Support/TouchpadServer")
    let file = dir.appendingPathComponent("pin")
    if !regenerate,
       let s = try? String(contentsOf: file, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines),
       s.count == 4 {
        return s
    }
    let pin = String(format: "%04d", Int.random(in: 0...9999))
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    try? pin.write(to: file, atomically: true, encoding: .utf8)
    return pin
}

func localIPv4Addresses() -> [String] {
    var result: [String] = []
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return result }
    defer { freeifaddrs(ifaddr) }
    for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
        let ifa = ptr.pointee
        guard let sa = ifa.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET),
              (ifa.ifa_flags & UInt32(IFF_UP)) != 0, (ifa.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
            result.append("\(String(cString: ifa.ifa_name)): \(String(cString: host))")
        }
    }
    return result
}

// MARK: - Event injection

final class Injector {
    let src = CGEventSource(stateID: .hidSystemState)
    var pos = CGEvent(source: nil)?.location ?? .zero
    var lastPosted = CGPoint(x: -1e9, y: -1e9)
    var buttonsDown = Set<Int>()
    var clickState = [1, 1, 1]

    var lastClickTime = 0.0
    var lastClickPos = CGPoint.zero
    var lastClickButton = -1
    var clickCount = 0

    var scrollRemX = 0.0, scrollRemY = 0.0
    var momentumTimer: DispatchSourceTimer?
    var momentumStarted = false
    var mvx = 0.0, mvy = 0.0, lastTick = 0.0

    // Re-sync with the real cursor if a physical mouse/trackpad moved it.
    func syncPos() {
        guard let real = CGEvent(source: nil)?.location else { return }
        if hypot(real.x - lastPosted.x, real.y - lastPosted.y) > 2 { pos = real }
    }

    func clamp(_ p: CGPoint, from old: CGPoint) -> CGPoint {
        var ids = [CGDirectDisplayID](repeating: 0, count: 16)
        var count: UInt32 = 0
        CGGetActiveDisplayList(16, &ids, &count)
        let rects = ids.prefix(Int(count)).map { CGDisplayBounds($0) }
        if rects.isEmpty || rects.contains(where: { $0.contains(p) }) { return p }
        let r = rects.first(where: { $0.contains(old) }) ?? CGDisplayBounds(CGMainDisplayID())
        return CGPoint(x: min(max(p.x, r.minX), r.maxX - 1), y: min(max(p.y, r.minY), r.maxY - 1))
    }

    func move(dx: Double, dy: Double) {
        syncPos()
        let p = clamp(CGPoint(x: pos.x + dx, y: pos.y + dy), from: pos)
        pos = p
        let type: CGEventType
        let button: CGMouseButton
        if buttonsDown.contains(0) { type = .leftMouseDragged; button = .left }
        else if buttonsDown.contains(1) { type = .rightMouseDragged; button = .right }
        else if buttonsDown.contains(2) { type = .otherMouseDragged; button = .center }
        else { type = .mouseMoved; button = .left }
        guard let e = CGEvent(mouseEventSource: src, mouseType: type, mouseCursorPosition: p, mouseButton: button) else { return }
        e.setIntegerValueField(.mouseEventDeltaX, value: Int64(dx.rounded()))
        e.setIntegerValueField(.mouseEventDeltaY, value: Int64(dy.rounded()))
        if type != .mouseMoved { e.setIntegerValueField(.mouseEventClickState, value: Int64(clickState[button == .left ? 0 : button == .right ? 1 : 2])) }
        e.post(tap: .cghidEventTap)
        lastPosted = p
    }

    func nextClickCount(_ b: Int) -> Int {
        let t = now()
        if b == lastClickButton, t - lastClickTime < NSEvent.doubleClickInterval,
           hypot(pos.x - lastClickPos.x, pos.y - lastClickPos.y) < 8 {
            clickCount += 1
        } else {
            clickCount = 1
        }
        lastClickTime = t; lastClickPos = pos; lastClickButton = b
        return clickCount
    }

    func button(_ b: Int, down: Bool, count: Int? = nil) {
        guard (0...2).contains(b) else { return }
        syncPos()
        if down {
            if let c = count {
                clickCount = c; lastClickTime = now(); lastClickPos = pos; lastClickButton = b
                clickState[b] = c
            } else {
                clickState[b] = nextClickCount(b)
            }
            buttonsDown.insert(b)
        } else {
            guard buttonsDown.remove(b) != nil else { return }
        }
        let types: [(CGEventType, CGEventType, CGMouseButton)] = [
            (.leftMouseDown, .leftMouseUp, .left),
            (.rightMouseDown, .rightMouseUp, .right),
            (.otherMouseDown, .otherMouseUp, .center),
        ]
        let (dt, ut, mb) = types[b]
        guard let e = CGEvent(mouseEventSource: src, mouseType: down ? dt : ut, mouseCursorPosition: pos, mouseButton: mb) else { return }
        e.setIntegerValueField(.mouseEventClickState, value: Int64(clickState[b]))
        e.post(tap: .cghidEventTap)
        lastPosted = pos
    }

    func click(_ b: Int) {
        button(b, down: true)
        button(b, down: false)
    }

    func releaseAll() {
        for b in buttonsDown { button(b, down: false) }
        stopMomentum()
    }

    // MARK: Scrolling

    func postScroll(phase: Int64, momentum: Int64, dx: Double, dy: Double) {
        scrollRemX += dx; scrollRemY += dy
        let ix = scrollRemX.rounded(.towardZero), iy = scrollRemY.rounded(.towardZero)
        scrollRemX -= ix; scrollRemY -= iy
        guard let e = CGEvent(scrollWheelEvent2Source: src, units: .pixel, wheelCount: 2,
                              wheel1: Int32(iy), wheel2: Int32(ix), wheel3: 0) else { return }
        e.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
        e.setIntegerValueField(.scrollWheelEventScrollPhase, value: phase)
        e.setIntegerValueField(.scrollWheelEventMomentumPhase, value: momentum)
        e.setIntegerValueField(.scrollWheelEventPointDeltaAxis1, value: Int64(iy))
        e.setIntegerValueField(.scrollWheelEventPointDeltaAxis2, value: Int64(ix))
        e.post(tap: .cghidEventTap)
    }

    func scrollBegin(dx: Double, dy: Double) {
        stopMomentum()
        scrollRemX = 0; scrollRemY = 0
        postScroll(phase: Phase.began, momentum: Momentum.none, dx: dx, dy: dy)
    }

    func scrollChange(dx: Double, dy: Double) {
        postScroll(phase: Phase.changed, momentum: Momentum.none, dx: dx, dy: dy)
    }

    func scrollEnd(vx: Double, vy: Double) {
        postScroll(phase: Phase.ended, momentum: Momentum.none, dx: 0, dy: 0)
        guard hypot(vx, vy) > 80 else { return }
        mvx = vx; mvy = vy
        momentumStarted = false
        lastTick = now()
        let t = DispatchSource.makeTimerSource(queue: .main)
        t.schedule(deadline: .now() + 1.0 / 120, repeating: 1.0 / 120, leeway: .milliseconds(1))
        t.setEventHandler { [weak self] in self?.momentumTick() }
        momentumTimer = t
        t.resume()
    }

    func momentumTick() {
        let t = now()
        let dt = min(t - lastTick, 0.05)
        lastTick = t
        let decay = pow(0.9975, dt * 1000) // ~0.4s time constant, close to macOS feel
        mvx *= decay; mvy *= decay
        if hypot(mvx, mvy) < 15 { stopMomentum(); return }
        postScroll(phase: 0, momentum: momentumStarted ? Momentum.cont : Momentum.begin, dx: mvx * dt, dy: mvy * dt)
        momentumStarted = true
    }

    func stopMomentum() {
        guard let t = momentumTimer else { return }
        t.cancel()
        momentumTimer = nil
        if momentumStarted { postScroll(phase: 0, momentum: Momentum.end, dx: 0, dy: 0) }
        momentumStarted = false
    }

    // MARK: Gestures (pinch / rotate) — same private event fields a real trackpad produces

    func gesture(subtype: Int64, phase: Int64, valueField: UInt32, value: Double) {
        guard let e = CGEvent(source: nil) else { return }
        e.setIntegerValueField(field(55), value: 29) // NSEventTypeGesture
        e.setIntegerValueField(field(110), value: subtype)
        e.setIntegerValueField(field(132), value: phase)
        e.setDoubleValueField(field(valueField), value: value)
        e.post(tap: .cghidEventTap)
    }

    func magnify(phase: Int64, amount: Double) { gesture(subtype: 8, phase: phase, valueField: 113, value: amount) }
    func rotate(phase: Int64, degrees: Double) { gesture(subtype: 5, phase: phase, valueField: 114, value: degrees) }

    // MARK: Keyboard shortcuts for system gestures

    func key(_ code: CGKeyCode, _ flags: CGEventFlags) {
        for down in [true, false] {
            guard let e = CGEvent(keyboardEventSource: src, virtualKey: code, keyDown: down) else { continue }
            e.flags = flags
            e.post(tap: .cghidEventTap)
        }
    }

    func command(_ name: String) {
        let ctrlFn: CGEventFlags = [.maskControl, .maskSecondaryFn]
        switch name {
        case "mission": key(126, ctrlFn)           // ^↑ Mission Control
        case "expose": key(125, ctrlFn)            // ^↓ App windows
        case "spaceL": key(123, ctrlFn)            // ^← previous Space
        case "spaceR": key(124, ctrlFn)            // ^→ next Space
        case "desktop": key(103, .maskSecondaryFn) // F11 Show Desktop
        case "lookup": key(2, [.maskControl, .maskCommand]) // ^⌘D Look Up
        case "back": key(33, .maskCommand)         // ⌘[
        case "forward": key(30, .maskCommand)      // ⌘]
        case "apps":
            for path in ["/System/Applications/Apps.app", "/System/Applications/Launchpad.app"]
            where FileManager.default.fileExists(atPath: path) {
                NSWorkspace.shared.open(URL(fileURLWithPath: path))
                break
            }
        default: break
        }
    }
}

// MARK: - Server

final class Server {
    let pin: String
    let injector = Injector()
    let name = Host.current().localizedName ?? ProcessInfo.processInfo.hostName
    var fd: Int32 = -1
    var client: sockaddr_in?
    var lastPacket = 0.0
    var failures: [UInt32: (count: Int, until: Double)] = [:]

    init(pin: String) { self.pin = pin }

    func start() {
        fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { fatalError("socket() failed") }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY
        let ok = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard ok == 0 else {
            print("Could not listen on UDP port \(port) — is another copy of the server already running?")
            exit(1)
        }

        Thread { [self] in receiveLoop() }.start()

        // Safety net: if the tablet disappears mid-drag, let go of the mouse button.
        let watchdog = DispatchSource.makeTimerSource(queue: .main)
        watchdog.schedule(deadline: .now() + 1, repeating: 1)
        watchdog.setEventHandler { [self] in
            if !injector.buttonsDown.isEmpty, now() - lastPacket > 3 { injector.releaseAll() }
        }
        watchdog.resume()
        self.watchdog = watchdog
    }
    var watchdog: DispatchSourceTimer?

    func receiveLoop() {
        var buf = [UInt8](repeating: 0, count: 2048)
        while true {
            var from = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, &buf, buf.count, 0, $0, &len) }
            }
            guard n > 0, let text = String(bytes: buf[0..<n], encoding: .utf8) else { continue }
            let sender = from
            DispatchQueue.main.async { [self] in
                for line in text.split(separator: "\n") { handle(String(line), from: sender) }
            }
        }
    }

    func reply(_ s: String, to addr: sockaddr_in) {
        var a = addr
        let bytes = Array(s.utf8)
        _ = withUnsafePointer(to: &a) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                sendto(fd, bytes, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
    }

    func same(_ a: sockaddr_in, _ b: sockaddr_in) -> Bool {
        a.sin_addr.s_addr == b.sin_addr.s_addr && a.sin_port == b.sin_port
    }

    func describe(_ a: sockaddr_in) -> String { String(cString: inet_ntoa(a.sin_addr)) }

    func handle(_ line: String, from: sockaddr_in) {
        let parts = line.split(separator: " ").map(String.init)
        guard let cmd = parts.first else { return }
        let num = { (i: Int) -> Double in i < parts.count ? Double(parts[i]) ?? 0 : 0 }

        if cmd == "DISCOVER" { reply("TOUCHPAD \(name)", to: from); return }

        if cmd == "HELLO" {
            let ip = from.sin_addr.s_addr
            if let f = failures[ip], f.count >= 5, now() < f.until { return }
            if parts.count > 1, parts[1] == pin {
                failures[ip] = nil
                if client == nil || !same(client!, from) {
                    if client != nil { injector.releaseAll() }
                    print("Tablet connected from \(describe(from))")
                }
                client = from
                lastPacket = now()
                reply("OK \(name)", to: from)
            } else {
                var f = failures[ip] ?? (0, 0)
                f.count += 1
                if f.count >= 5 { f.until = now() + 60 }
                failures[ip] = f
                reply("BAD", to: from)
            }
            return
        }

        guard let c = client, same(c, from) else { reply("AUTH", to: from); return }
        lastPacket = now()
        let inj = injector

        switch cmd {
        case "M": inj.move(dx: num(1), dy: num(2))
        case "C": inj.click(Int(num(1)))
        case "D": inj.button(Int(num(1)), down: true, count: parts.count > 2 ? Int(num(2)) : nil)
        case "U": inj.button(Int(num(1)), down: false)
        case "T": inj.stopMomentum()
        case "S":
            switch parts.count > 1 ? parts[1] : "" {
            case "B": inj.scrollBegin(dx: num(2), dy: num(3))
            case "C": inj.scrollChange(dx: num(2), dy: num(3))
            case "E": inj.scrollEnd(vx: num(2), vy: num(3))
            default: break
            }
        case "Z", "R":
            let phase: Int64 = ["B": Phase.began, "C": Phase.changed, "E": Phase.ended][parts.count > 1 ? parts[1] : ""] ?? Phase.changed
            if cmd == "Z" { inj.magnify(phase: phase, amount: num(2)) } else { inj.rotate(phase: phase, degrees: num(2)) }
        case "K": if parts.count > 1 { inj.command(parts[1]) }
        case "BYE":
            inj.releaseAll()
            client = nil
            print("Tablet disconnected")
        default: break
        }
    }
}

// MARK: - Main

setvbuf(stdout, nil, _IOLBF, 0)
let pin = loadPin(regenerate: CommandLine.arguments.contains("--new-pin"))

let trusted = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary)

print("""

  ┌──────────────────────────────────────────┐
  │   Touchpad server is running             │
  └──────────────────────────────────────────┘
  PIN:  \(pin)
  Port: \(port) (UDP)
  This Mac's addresses:
""")
for a in localIPv4Addresses() { print("    \(a)") }
print("")
print("  On the tablet: open Touchpad → tap the status pill → Find Mac → enter the PIN.")
print("  Press Ctrl+C to stop.\n")

if !trusted {
    print("""
  ⚠️  Accessibility permission is needed to control the pointer.
     System Settings → Privacy & Security → Accessibility → enable the app
     you ran this from (e.g. Terminal), then restart this server.

""")
}

let server = Server(pin: pin)
server.start()
RunLoop.main.run()
