# Kyant AndroidLiquidGlass 1.0.6 source port

Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0.
The complete upstream license is included in the adjacent `LICENSE` file.
Every Kotlin file retains the copyright, license notice and its original source URL.

The five Kotlin files in the application's `ui/nav/kyant` package are derived directly from
[Kyant0/AndroidLiquidGlass tag 1.0.6](https://github.com/Kyant0/AndroidLiquidGlass/tree/1.0.6),
commit `896a94a3ade1cc1a940b92365f942a34971fecda`:

- `catalog/src/main/java/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt`
- `catalog/src/main/java/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt`
- `catalog/src/main/java/com/kyant/backdrop/catalog/utils/DampedDragAnimation.kt`
- `catalog/src/main/java/com/kyant/backdrop/catalog/utils/DragGestureInspector.kt`
- `catalog/src/main/java/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt`

## Changes for UPC Pocket

- Relocated the Kotlin package to `com.upcpocket.app.ui.nav.kyant` and added explicit source/license headers.
- Renamed the controlled selection parameter to `selectedIndex` and added an explicit `darkTheme` input so the host can honor its in-app theme setting.
- Kept the original component-owned drag state, spring constants, velocity deformation, and panel displacement, while tightening the selected capsule to 40dp inside a 56dp offscreen-composited (`CompositingStrategy.Offscreen`) clipped bar so it stays clear of the outer rim.
- Draw the panel with a higher-opacity frosted theme tint (`0.88` light / `0.90` dark), highlight, and clipped outer border. The selected indicator sits behind the navigation icons and samples only the shared static theme backdrop during interaction, removing the hidden duplicate icon refraction layer to prevent double-icon ghosting.
- Kept updated host selection/callback references and recreate geometry-dependent state when tab count, width or layout direction changes. Host selection changes do not echo into `onTabSelected`, which would otherwise restart a Pager navigation.
- Added selected-tab semantics and a selectable group. The provided `LiquidBottomTab` disables click/focus targets for the hidden refraction copy while preserving its drawing.
- Resolve a tap intercepted by the moving lens against the touched tab in the fixed navigation bar. Drag gestures still use the original settling logic.
- Return without drawing for an empty tab collection. The host supplies bounded width, valid tabs, accessible labels, insets, the matching library dependencies and its API 31/32 fallback.

The helper implementations otherwise retain the original 1.0.6 behavior. This source port does not claim to implement an additional pointed droplet trail or to improve upstream performance.

## PR #9 comparison

[UPCPocket PR #9](https://github.com/dlam12138/UPCPocket/pull/9), inspected at
head `e5e534f15fb8d9b1fe41b9b3a89402b0af6c0d3c`, is a separate adaptation.
Its latest `LiquidBottomTabs` removes the hidden `tabsBackdrop` row and samples the page alone;
its helper also adds direct dragging and predictive settling. Those changes are not used here,
because this port keeps the original Kyant 1.0.6 motion and combined sources for the moving capsule.

The application bundles this notice and the adjacent license as assets.
