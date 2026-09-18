import SwiftUI

// Deliberately the whole Swift side, minus the UIViewController bridge next to it.
//
// The claim Compose Multiplatform makes is that iOS needs no second implementation of the UI, and
// a sample that quietly wrote one in Swift would be demonstrating the opposite. Everything you see
// when this runs — the app screen and the inspector overlay on top of it — is the same Kotlin the
// Android sample runs.
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView().ignoresSafeArea(.all)
        }
    }
}
