# Tutorial: Fitting a Mixture of Distributions

**Packages:** `ksl.utilities.distributions.fitting.mixture` and its `partition`, `refine`,
`scoring` and `search` subpackages, `ksl.utilities.distributions.fitting.diagnostics`, and the
mixture report extensions in `ksl.utilities.io.report.extensions`.

**New in R1.7.**

> **Status: experimental.** These packages are released as experimental. Their public API may
> change in future releases without notice. Pin your KSL version if you build against them for
> production use.

A hands-on walkthrough in eight steps, each backed by a runnable file. For the API reference —
what the key types are and when to reach for each — see [`ksl-mixture`](ksl-mixture.md).

Every example runs against the same dataset, which ships with the examples in
`KSLExamples/chapterFiles/Appendix-Distribution Fitting`. You can open it, plot it in R, or load
it into the Distribution app; every run of every example sees the same numbers.

The runnable files are in
`KSLExamples/src/main/kotlin/ksl/examples/general/utilities/fitting/mixture/`.

---

## Table of contents

- [Part I — The problem](#part-i--the-problem)
- [Part II — Fitting a mixture](#part-ii--fitting-a-mixture)
- [Part III — Choosing the number of components](#part-iii--choosing-the-number-of-components)
- [Part IV — Taking the facade apart](#part-iv--taking-the-facade-apart)
- [Part V — Judging the answer against a known truth](#part-v--judging-the-answer-against-a-known-truth)
- [Part VI — Your own data](#part-vi--your-own-data)
- [Part VII — The diagnostics](#part-vii--the-diagnostics)
- [Part VIII — How much should you believe it?](#part-viii--how-much-should-you-believe-it)
- [Part IX — One report with all of it](#part-ix--one-report-with-all-of-it)
- [Appendix A — The runnable files](#appendix-a--the-runnable-files)

---

## Part I — The problem

*Runnable: `Example1TheProblem.kt`*

Service times at a hospital reception desk come from three kinds of visitor. Booked check-ins are
quick, new registrations take longer, and a few complex cases take much longer still. That is one
queue and one measured quantity, but it is not one distribution.

Fit the catalog to it the ordinary way and something uncomfortable happens: every family fits
badly, and the tool still recommends one. It has no way to say "none of these has the right
shape" — a score is a comparison among the candidates offered, not a verdict on whether any of
them belongs.

Start by reading the data:

```kotlin
val data = receptionDeskData()
val holdOut = receptionDeskHoldOut()
```

Run `Example1TheProblem.kt` and look at the fit plot it opens. The recommendation is the best of
a bad set.

---

## Part II — Fitting a mixture

*Runnable: `Example2FittingAMixture.kt`*

Three lines do the work:

```kotlin
val data = receptionDeskData()
val modeler = MixtureModeler(data)
val results = modeler.fit(numComponentsRange = 1..6)
println(results)
```

The modeler sorts the data, cuts it into contiguous groups, fits every family in the catalog to
each group, assembles the best combination, and repeats that for each component count you asked
for.

The recommendation comes apart into its components:

```kotlin
val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
val best = results.best ?: return
val mixture = best.candidate
for (j in 0 until mixture.numComponents) {
    println("component ${j + 1}: weight = ${mixture.weights[j]}, ${mixture.components[j].name}")
}
```

**The payoff is that the result is an ordinary KSL distribution.** It has a mean, a CDF, an
inverse CDF, and it will hand you a random variable to drive a simulation — which is usually the
reason you were fitting anything:

```kotlin
val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
val fitted = results.best!!.distribution
println(fitted.mean())
println(fitted.invCDF(0.5))
val generated = fitted.randomVariable(streamNumber = 99).sample(5)
```

---

## Part III — Choosing the number of components

*Runnable: `Example3ChoosingK.kt`*

Adding a component can only improve the fit, so likelihood alone would always pick the largest
number you offered. A criterion adds a penalty for complexity. Several are available, and **they
do not agree** — so report them side by side rather than combining them. Averaging criteria
manufactures an agreement the evidence does not support.

One fit scores under all of them, because every criterion is evaluated on the same candidates
afterwards:

```kotlin
val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
println(results.criterionSummary())
```

Two things to look for, and the second is easy to miss:

1. Where each criterion's smallest value falls.
2. **Whether that smallest value sits at the largest k you offered.** If it does, the criterion
   did not find a best answer — it ran out of candidates, and would keep going if you widened the
   range. That is a completely different situation, and it looks identical if you print only the
   winner.

Which is why the profile is worth printing, not just the choice:

```kotlin
val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
for ((k, ranked) in results.bestByNumComponents().toSortedMap()) {
    println("k = $k: ${ranked.criterionValue.value}")
}
```

Recovering the true component count is genuinely hard. On data where the truth is known, the best
of these criteria gets it right well under half the time. Part VIII is about what to do with that.

---

## Part IV — Taking the facade apart

*Runnable: `Example4Refinement.kt`*

`MixtureModeler` assembles five pieces for you, and each is an interface you can replace: a
partition generator, a refiner, a component fitter, a criterion, and a family selector. Here they
are separately:

```kotlin
val sorted = receptionDeskData().sortedArray()
val k = 3
val certificate = AdmissibilityCertificate(sorted)
val fitter = ComponentFitCache(PDFComponentFitter(PDFModeler.allEstimators, false))
val criterion = MixtureBICCriterion()
val selector = SeparableCMLSelector()
val initial = JenksPartitionGenerator(sorted, k).generate(sorted, k, certificate) ?: return
```

Note the `ComponentFitCache` wrapping the fitter. Fitting a group means running the whole
estimator catalog over it, which dominates the cost of everything else; refinement moves one cut
at a time, so consecutive partitions share most of their groups. The facade applies this for you,
and when you assemble the pipeline yourself you should too.

Two refiners improve an initial partition, and they work quite differently:

```kotlin
val fitter = ComponentFitCache(PDFComponentFitter(PDFModeler.allEstimators, false))
val shifted = BreakShiftRefiner().refine(sorted, initial, certificate, fitter)
val classified = ClassificationEMRefiner().refine(sorted, initial, certificate, fitter)
```

`BreakShiftRefiner` nudges one cut at a time and keeps a move if it helps. `ClassificationEMRefiner`
solves the assignment problem *exactly* with a dynamic program, refits, and repeats.

You would expect the exact one to win. Often it does not — solving one sub-problem perfectly is
not the same as solving the whole problem. Run the example and see which wins on this data, then
remember that one dataset settles nothing.

---

## Part V — Judging the answer against a known truth

*Runnable: `Example5AgainstTheTruth.kt`*

Because this data was simulated, we know what generated it, and can ask two quite different
questions that get two quite different answers.

**Is the fitted density close to the true density?** Usually yes:

```kotlin
val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
val mixture = results.best!!.candidate
val recovery = RecoveryMeasures.compare(
    receptionDeskWeights, receptionDeskComponents, receptionDeskFamilies,
    mixture.weights, mixture.distributions, mixture.components.map { it.rvType }
)
println(recovery.hellinger)
println(recovery.numComponentsCorrect)
```

**Did it identify the right components?** Usually not.

That distinction decides what the method is good for. For input modelling — generating service
times that behave like the real ones — the first question is the one that matters. For claiming
"there are three kinds of visitor and here is what each looks like", it is the second, and the
method is much weaker there.

`RecoveryMeasures` compares densities rather than component labels, deliberately. Those labels
are not always identifiable: an exponential is a gamma of shape one, a uniform is a beta with
both shapes one, and a comparison keyed on the label would report failures that are not failures.

A model that fits the data it was built from and nothing else has learned nothing, so ask it
about data it never saw:

```kotlin
val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
val mixture = results.best!!.candidate
val evaluation = mixture.logLikelihood(receptionDeskHoldOut())
if (evaluation.isUsable) {
    println(evaluation.value / receptionDeskHoldOut().size)
}
```

The `isUsable` check is not ceremony. If any held-out observation lands where the fitted mixture
has zero density, the log-likelihood is negative infinity and the average is meaningless.

---

## Part VI — Your own data

*Runnable: `Example6YourOwnData.kt`*

```kotlin
val file = KSLFileUtil.chooseFile() ?: return
val data = KSLFileUtil.scanToArray(file.toPath())
```

On real data there is no truth to compare against, so the checks change. Split the data, fit the
first half, check against the second, and ask three weaker questions: does the mixture beat the
best single distribution, does it account for data it was not fitted to, and does the picture look
right?

Those are weaker than knowing the answer, and it is worth being honest that they are.

---

## Part VII — The diagnostics

*Runnable: `Example7Diagnostics.kt`*

A fitted mixture is an ordinary continuous distribution, so every diagnostic the library already
offers applies to it unchanged — the four-panel fit plot, and the Anderson–Darling, Cramér–von
Mises and Kolmogorov–Smirnov tests.

You rarely need to assemble any of that yourself. One line produces the standard report — every
section below, plots included, as a self-contained HTML page:

```kotlin
val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
results.showHTMLInBrowser(heldOut = receptionDeskHoldOut())
```

The plots and the written report side by side:

```kotlin
val data = receptionDeskData()
val holdOut = receptionDeskHoldOut()
val results = MixtureModeler(data).fit(numComponentsRange = 1..6)
results.showAllResultsInBrowser(heldOut = holdOut)
val report = results.showHTMLInBrowser(heldOut = holdOut)
```

The results object answers the question you actually have — **was a mixture worth it?** — by
comparing the recommendation against the best single distribution. That comparison is exact
rather than approximate, because a one-component mixture *is* a single distribution, fitted from
the same catalog and ranked by the same criterion.

The same results are available as data frames for anything you want to do afterwards:

```kotlin
val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
println(results.componentsAsDataFrame())
println(results.criterionProfileAsDataFrame())
```

**A caution the summary cannot make for you.** Passing a goodness-of-fit test is weaker evidence
than it looks when the distribution was chosen by looking at the same data — and both fits here
were. Read the tests as a comparison between the two rather than as an endorsement of either. The
held-out coverage line is the honest check.

---

## Part VIII — How much should you believe it?

*Runnable: `Example8HowStableIsIt.kt`*

A fit reports one number of components. It does not say whether a slightly different sample would
have produced the same answer — and for this method, very often it would not.

Refitting on resampled data answers that directly:

```kotlin
val data = receptionDeskData()
val boot = MixtureBootstrap.componentCountFrequency(
    data,
    numBootstrapSamples = 100,
    numComponentsRange = 1..6
)
println(boot)
```

A single tall bar means the component count is well determined by the data. Several comparable
bars mean it is not, and any single number you quote from one fit is one draw from that spread.

There is a second version, which samples from the fitted mixture itself rather than from the data:

```kotlin
val data = receptionDeskData()
val results = MixtureModeler(data).fit(numComponentsRange = 1..6)
val parametric = MixtureBootstrap.parametricComponentCountFrequency(
    results.best!!.distribution,
    sampleSize = data.size,
    numBootstrapSamples = 100,
    numComponentsRange = 1..6
)
println(parametric)
```

The two disagree in an informative way. The parametric version asks how well the procedure
recovers an answer it was handed; the nonparametric one asks how much the data pin the answer
down. Neither alone is the whole story.

This costs a full refit per resample — about 30 seconds for the 200 resamples above on the
tutorial data. Start small on your own data before asking for hundreds.

---

## Part IX — One report with all of it

Parts I through VIII each compute one piece of the picture: what the data looked like before
fitting, which component counts the criteria preferred, whether the cuts matter, whether the fit is
adequate, and how much the answer moves under resampling. **The standard report takes all of them
at once**, so the walkthrough you have just done by hand becomes a single page you can send to
someone:

```kotlin
val data = receptionDeskData()
val modeler = MixtureModeler(data)
val results = modeler.fit(numComponentsRange = 1..6)
val report = results.showHTMLInBrowser(
    heldOut = receptionDeskHoldOut(),
    title = "Reception desk service times",
    description = modeler.describe(),
    stability = modeler.partitionStability(results),
    adequacy = modeler.assessFitAdequacy(results),
    bootstrap = MixtureBootstrap.componentCountFrequency(data, numBootstrapSamples = 100),
    variableName = "service time (minutes)"
)
println(report.absolutePath)
```

Each optional argument adds its own section, and leaving one out simply omits that section — so
start with `heldOut` alone and add the others as you decide you want them. They are not free:
`assessFitAdequacy` refits on simulated replicates and `componentCountFrequency` refits once per
resample, which is where the time goes.

To keep a report rather than open one:

```kotlin
val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
val html = results.asHTML(heldOut = receptionDeskHoldOut())
val markdown = results.asMarkdown(heldOut = receptionDeskHoldOut())
```

`asMarkdown` defaults to omitting plots, which is what you want for a report that will be read in a
terminal or committed to a repository.

The report is a document you can add to. The trailing block appends your own sections using the
same builder the report itself is written with:

```kotlin
val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
val document = results.toReport(title = "Reception desk service times") {
    heading("Why we fitted this", level = 2)
    paragraph("Three kinds of visitor share one queue, so one distribution will not do.")
}
```

That matters more than it looks. The report states what the fit found; it cannot state why anyone
fitted it, what the data is, or what decision hangs on it — and a reader six months from now needs
those more than another table.

---

## Appendix A — The runnable files

All in `KSLExamples/src/main/kotlin/ksl/examples/general/utilities/fitting/mixture/`, each with a
`main`, meant to be read in order.

| File | Part | Opens a window? |
|---|---|---|
| `Example1TheProblem.kt` | I | yes — histogram and fit plot |
| `Example2FittingAMixture.kt` | II | no |
| `Example3ChoosingK.kt` | III | no |
| `Example4Refinement.kt` | IV | no |
| `Example5AgainstTheTruth.kt` | V | no |
| `Example6YourOwnData.kt` | VI | yes — a file chooser |
| `Example7Diagnostics.kt` | VII | yes — plots and a report |
| `Example8HowStableIsIt.kt` | VIII | no (about 30 s) |

The data is `ReceptionDeskServiceTimes.txt` and `ReceptionDeskHoldOut.txt`, in
`KSLExamples/chapterFiles/Appendix-Distribution Fitting`, beside the book's other input-modeling
datasets.

## See also

- [`ksl-mixture`](ksl-mixture.md) — the API reference for these packages.
- [`ksl-utilities-distributions-fitting`](ksl-utilities-distributions-fitting.md) — fitting a
  single distribution, which is where to start when one shape will do.
- [`ksl-utilities-distributions`](ksl-utilities-distributions.md) — the distributions themselves.
