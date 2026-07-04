// Standalone nested Gradle project — deliberately NOT part of the KSL root build
// (it is not listed in ../settings.gradle.kts), exactly like KSLProjectTemplate.
// It reads the sibling KSLCore/KSLExamples *source* at build time to generate its
// search index, but ships as its own independently-versioned jar so the student
// tool is decoupled from the library build. Build it from this directory:
//   ./gradlew shadowJar
rootProject.name = "KSLCodeMCPServer"
