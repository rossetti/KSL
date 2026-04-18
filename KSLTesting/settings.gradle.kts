rootProject.name = "KSLTesting"

include(":KSLCore")
include(":KSLExamples")

project(":KSLCore").projectDir = file("../KSLCore")
project(":KSLExamples").projectDir = file("../KSLExamples")
