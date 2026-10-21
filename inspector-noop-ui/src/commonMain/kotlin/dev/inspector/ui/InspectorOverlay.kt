// The file name is part of the ABI. `InspectorOverlay` compiles into a JVM class named for this
// file, `InspectorOverlayKt`, and a consumer's compiled call names that class. The twin module
// declares it in a file of the same name, so a build compiled against one and run against the
// other links. Renaming either file is a breaking change for every consumer on Android and JVM.
//
// It cannot be pinned with `@file:JvmName`: this is a common source set shared with Native, where
// `kotlin.jvm.JvmName` does not resolve. `UiApiParityTest` names the facade instead and fails in
// this module if the file is renamed. Do not rename it without reading that test.

package dev.inspector.ui

import androidx.compose.runtime.Composable

/**
 * Release-build stand-in for `:inspector-ui`'s overlay: renders the app and nothing else.
 *
 * Signature-identical to the real one so `InspectorOverlay { App() }` in the consuming app
 * compiles unchanged under `-Pinspector=off`.
 */
@Composable
fun InspectorOverlay(
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    content()
}
