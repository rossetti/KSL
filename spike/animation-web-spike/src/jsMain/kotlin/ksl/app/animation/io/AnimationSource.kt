package ksl.app.animation.io

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader

/**
 * PHASE S SPIKE — `AnimationSource` with the plan's design note applied: `baseDir: java.nio.file.Path`
 * becomes `assetBase: String?` (a directory path OR a URL prefix), which is what makes the class
 * commonMain-clean. The JVM `load(Path, Path)` factory stays on the JVM side of the boundary.
 */
class AnimationSource(
    val layout: AnimationLayout?,
    val header: AnimationTraceHeader,
    val events: List<AnimationEvent>,
    val assetBase: String? = null
)
