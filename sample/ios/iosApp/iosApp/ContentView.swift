import SwiftUI
import SampleShared

// Hosts the Kotlin UIViewController that `MainViewController()` builds.
//
// `ignoresSafeArea` on purpose: Compose is given the whole screen, and the overlay insets its own
// screens with `Modifier.inspectorScreen`. Letting SwiftUI inset instead would hide the very thing
// a device run is here to check — that the inspector's background bleeds edge to edge while its
// content stays clear of the status bar and the home indicator.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea(.all)
    }
}
