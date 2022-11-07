rootProject.name = "KSLTesting"

include(":KSLCore")
include(":KSLExamples")
include(":KSLExtensions")

project(":KSLCore").projectDir = file("../KSLCore")
project(":KSLExamples").projectDir = file("../KSLExamples")
project(":KSLExtensions").projectDir = file("../KSLExtensions")
