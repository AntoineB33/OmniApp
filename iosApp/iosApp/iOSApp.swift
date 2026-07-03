import SwiftUI

@main
struct iOSApp: App {
    // Installs the APNs registration + pause-cue push routing (see AppDelegate.swift).
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
