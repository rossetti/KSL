rootProject.name = "KSL"

include(":KSLCore")
include(":KSLApp")
include(":KSLExamples")
include(":KSLTestModels")
include(":KSLTestSupport")
include(":KSLAppSwingCommon")
include(":KSLAppSwingSingle")
include(":KSLAppSwingScenario")
include(":KSLAppSwingExperiment")
include(":KSLAppSwingSimopt")
include(":KSLAppSwingDistribution")
include(":KSLAppSwingResults")
include(":KSLBundleTools")

// KSLProjectTemplate is intentionally NOT part of this build. It is a standalone
// starter project (its own settings.gradle.kts) that depends on the PUBLISHED
// Maven artifact io.github.rossetti:KSLCore — used as a student handout and to
// verify that a fresh external consumer can resolve and build against released
// KSLCore. Keeping it out of the root build avoids any chance of its dependency
// being substituted by the in-repo source project.
