# KSLProjectTemplate

A starter project for building your own simulation models with the
**[Kotlin Simulation Library (KSL)](https://github.com/rossetti/KSL)**. It depends on the
published `KSLCore` library, so you can clone it, open it in IntelliJ IDEA, and start
modeling right away.

- **KSL Book:** https://rossetti.github.io/KSLBook/
- **API Docs:** https://rossetti.github.io/KSLDocs/

## Requirements

- **JDK 21** — the same JDK you use in IntelliJ IDEA.
- For the bundle tasks below only: the **KSL suite** installed (it provides the `kslpkg`
  tool). See the
  [installation guide](https://github.com/rossetti/KSL/blob/main/docs/guides/apps/install.md).

## What's inside

```
src/main/kotlin/work/
    CoffeeShop.kt   a small process-view model, its ModelBuilderIfc, and a main()
    TestKSL.kt      a Monte-Carlo statistics demo with a main()
```

Update the KSL release in `build.gradle.kts` when new versions ship:

```kotlin
api("io.github.rossetti:KSLCore:R1.4")
```

## Run it from the IDE

Open the project in IntelliJ IDEA and run the `main` function in either file
(`work/CoffeeShop.kt` or `work/TestKSL.kt`). That is the normal edit–run–inspect loop while
you develop a model.

## Turn your model into a bundle

A **bundle** is a JAR the KSL desktop apps and the KSL Server can load, so you can compare
scenarios, run designed experiments, optimize, and browse results without writing more code.
This template ships two Gradle tasks that build one for you:

```bash
./gradlew deployBundle
```

- **`assembleBundle`** builds your JAR and assembles it into a bundle at
  `build/libs/KSLProjectTemplate-bundle.jar`.
- **`deployBundle`** does that and also copies the bundle into your `KSLWork/bundles/`
  folder, where the apps look for it.

Then open a KSL app (Single, Scenario, Experiment, or Simopt): your model appears in the
picker next to the shipped examples. Re-run the task and restart the app after you change the
model.

### Set your bundle's identity

Edit the block at the top of `build.gradle.kts`:

```kotlin
val bundleId          = "edu.example.mywork"   // reverse-DNS; make it unique
val bundleName        = "My Work"
val bundleVersion     = "1.0.0"
val bundleDescription = ""
```

Give `bundleId` a globally-unique reverse-DNS value (e.g. `edu.uark.<you>.<project>`) so your
bundle does not collide with anyone else's.

### Overrides (rarely needed)

The tasks locate `kslpkg` from your KSL install automatically. If your install is in a
non-standard place, or you want to deploy somewhere else:

| Override | Purpose |
|---|---|
| `-Pkslpkg.jar=/path/to/kslpkg.jar` | point directly at the tool |
| `-Pksl.home=/path/to/KSL` or `KSL_HOME` | the KSL install root |
| `-Pksl.workspace=/path/to/KSLWork` or `KSLWORK` | where `deployBundle` copies the bundle |

Only `assembleBundle` / `deployBundle` use `kslpkg`; an ordinary `./gradlew build` works even
when the KSL suite is not installed.

## Learn more

- [Bundle Tools guide (`kslpkg`)](https://github.com/rossetti/KSL/blob/main/docs/guides/apps/bundle-tools.md)
  — the full packaging workflow, and what belongs inside a bundle.
- [KSL apps: common UI & concepts](https://github.com/rossetti/KSL/blob/main/docs/guides/apps/common-ui.md)
