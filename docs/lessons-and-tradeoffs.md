> Part of the [Android Modernization case study](../README.md).

# Current Technical Debt & Next Steps

> Companion to [`modernization-guide.md`](./modernization-guide.md). That document is the
> *history* — how the app reached its modern state. This document is the *present and future* —
> the debt that remains in the shipped code today, with concrete evidence (file:line), why each
> item matters, and a worked plan to pay it down.
>
> Everything here was found by reading the current `master`/working tree, not the git log. Each
> item lists the evidence so you can verify it yourself.

---

## Priority matrix

| # | Item | Impact | Effort | Risk if untouched | Priority |
|---|------|--------|--------|-------------------|----------|
| 1 | Domain objects serialized as Gson-JSON inside nav routes | High | High | Production crashes, silent data loss | **P0** |
| 2 | Gson (reflection) instead of kotlinx.serialization | Med | Med | R8 keep-rule fragility, enables #1's failure mode | **P1** |
| 3 | Global mutable singletons (`EventBus`, `SnackbarController`) | Med | Low–Med | Cross-lifecycle event leaks, hard to test | **P2** |
| 4 | `BaseScreen` manual lifecycle bridge (Fragment-think in Compose) | Low–Med | Med | Non-idiomatic Compose, double-fire bugs | **P3** |

---

## P0 — 1. Domain objects passed as Gson-JSON inside navigation routes

### Evidence

`app/src/main/java/.../presentation/ui/NavExtensions.kt`:

```kotlin
fun NavController.navigateToTrainStats(params: ActivityData) {
    val json = Uri.encode(Gson().toJson(params))          // whole domain object → JSON → URL-encoded
    this.navigate("${Screen.TRAIN_STATS.name}/$json")     // …embedded in the route string
}

fun NavController.navigateToEditPost(
    activityData: ActivityData,
    forTrainStats: Boolean,
    openedNoteId: Long,
) {
    val json = Uri.encode(Gson().toJson(activityData))
    this.navigate("${Screen.EDIT_POST.name}/$json/$forTrainStats/$openedNoteId")
}
```

The receiving side, in the per-feature graph builders, decodes it back:

```kotlin
val task = backStackEntry.arguments?.getString(TASKS_ITEM_FULL)
    .jsonToObject(TaskFull::class.java, TaskFull.empty())
val plan = backStackEntry.arguments?.getString(LESSON_PLAN_ITEM)
    .jsonToObject(LessonPlan::class.java, LessonPlan.empty())
val invite = backStackEntry.arguments?.getString(INVITE_ITEM)
    .jsonToObject(InviteData::class.java, InviteData.empty())
```

And the decoder itself, in `app/src/main/java/.../data/Extensions.kt`:

```kotlin
fun <T> String?.jsonToObject(classOfT: Class<T>, default: T): T {
    if (this == null || this == "null") return default
    return try {
        Gson().fromJson<T>(Uri.decode(this), classOfT)
    } catch (e: Exception) {
        FirebaseCrashlytics.getInstance().recordException(e)   // <-- this is the smoking gun
        default
    }
}
```

### Why it matters

This is the single most consequential design smell in the codebase. It is *stringly-typed
navigation wearing a typed `AppNavigator` interface as a coat* — the interface looks type-safe, but
under the hood every rich argument is a JSON string in a URL.

- **You already know it fails in production.** Every decode path reports the exception to
  Crashlytics and then falls back to `T.empty()`. That means there is a live, recurring failure
  mode where the user taps through to a screen and silently gets an **empty object** instead of
  their data — no crash, no error message, just a blank/broken screen. Silent data loss is worse
  than a crash because it's invisible in metrics.
- **Route strings have practical length limits.** Large objects — `BasePlayModel`, scorecards,
  lists of students/photos/names in `navigateToMassReview(...)` — risk truncation. The bigger the
  object, the closer you are to the edge.
- **Encoding is fragile.** You've already had to special-case the literal string `"null"`, and any
  unescaped special character or nested quote is a latent bug.
- **It fights R8.** Gson is reflection-based; reflective (de)serialization of model classes is
  exactly what forces the ProGuard/R8 keep rules that caused the `fix/r8_full_mode` crash trilogy
  documented in the modernization guide. This item and item #2 are the same root cause.

### The fix (two options, recommended order)

**Option A — pass IDs, re-fetch in the destination ViewModel (preferred).**
You built a clean `:data-repository` + `:domain` layer precisely so screens can fetch their own
data. A destination should receive the *minimum identity* (an ID), and its `@HiltViewModel` should
load the object from the repository using `SavedStateHandle`:

```kotlin
// Route: "train_stats/{activityId}"
@HiltViewModel
class TrainStatsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getActivity: GetActivityUseCase,
) : ViewModel() {
    private val activityId: Long = checkNotNull(savedStateHandle["activityId"])
    val uiState = getActivity(activityId)            // single source of truth, always fresh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)
}
```

This eliminates the serialization class of bugs entirely, keeps data fresh (no stale snapshot
carried through the backstack), and shrinks payloads to a `Long`. The cost is an extra fetch — but
you already cache in Room, so it's cheap, and correctness beats a micro-optimization here.

**Option B — type-safe navigation (when you genuinely must carry the object).**
You are on `navigation-compose 2.9.8`, which supports **type-safe routes** (since 2.8). Define
`@Serializable` route classes and let the library marshal arguments through `SavedStateHandle`
using kotlinx.serialization (pairs with item #2):

```kotlin
@Serializable
data class EditPostRoute(val activityId: Long, val forTrainStats: Boolean, val openedNoteId: Long)

composable<EditPostRoute> { entry ->
    val route = entry.toRoute<EditPostRoute>()       // typed, no manual Gson, no Uri.encode
    EditPostScreen(route)
}
navController.navigate(EditPostRoute(activityId = 42, forTrainStats = true, openedNoteId = 7))
```

### Migration plan

1. Start with the **highest-risk routes** — the ones carrying the largest objects/lists
   (`navigateToMassReview`, play-flow `BasePlayModel`/scorecard routes). These are the ones most
   likely already failing.
2. Convert **one feature graph at a time**; keep the `AppNavigator` interface (it's the right
   abstraction) but change the *method signatures* to take IDs and the *implementations* to use
   type-safe routes.
3. Delete the `jsonToObject`/`jsonToList` helpers from `Extensions.kt` once the last caller is gone.
4. Remove the now-unneeded `Crashlytics.recordException` fallbacks — the failure mode disappears.

### Effort / risk

High effort (touches every rich route), but it is the highest-payoff item: it removes a live
production failure mode. Do it under the cover of the existing test suite.

---

## P1 — 2. Gson (reflection-based) instead of kotlinx.serialization

### Evidence

`Gson()` is instantiated ad-hoc across the nav layer (`NavExtensions.kt`) and the decode helpers
(`Extensions.kt`, four `Gson().fromJson` call sites). You're on Kotlin 2.3 with the compiler plugin
already configured (`alias(libs.plugins.compose.compiler)`).

### Why it matters

- **Reflection ⇒ R8 friction.** Gson reflects over model classes at runtime, which is the reason
  you need keep rules and the reason R8 full mode crashed until those rules were added. Every model
  that travels through Gson is a class R8 must be told not to strip/rename.
- **It is the enabler of item #1's failure mode.** A compile-time-checked serializer would surface
  many of the problems that currently only show up as runtime `recordException` calls.
- **`new Gson()` per call** is also wasteful — Gson instances are meant to be reused; you're
  allocating one per (de)serialization.
- Gson is in maintenance-only mode; kotlinx.serialization is the Kotlin-first, compile-time,
  R8-friendly standard.

### The fix

1. Add the kotlinx.serialization plugin + runtime to the version catalog.
2. Annotate the domain models that travel through navigation/persistence with `@Serializable`.
   (Note: this dovetails with item #1 Option B — type-safe nav *uses* kotlinx.serialization.)
3. Replace `Gson().toJson/fromJson` with `Json.encodeToString/decodeFromString` where any JSON
   round-tripping survives item #1.
4. Audit Retrofit's converter — if it's Gson-based, consider moving it to kotlinx.serialization too
   for one consistent serializer and fewer keep rules. (Verify the converter before changing; this
   is a separate, larger sub-task.)

### Effort / risk

Medium. Do it *before* (or interleaved with) item #1 so the type-safe nav refactor lands directly
on kotlinx.serialization rather than migrating twice.

---

## P2 — 3. Global mutable singletons for cross-cutting concerns

### Evidence

`app/src/main/java/.../presentation/events/EventBus.kt`:

```kotlin
object EventBus {                                   // global, process-wide
    private val _events = MutableSharedFlow<GlobalEvents>()
    val events = _events.asSharedFlow()
    suspend fun post(event: GlobalEvents) { _events.emit(event) }
}
```

Also `SnackbarController.kt` (26 lines) follows the same global-object pattern.

### Why it matters

The implementation itself is fine (`SharedFlow` + a lifecycle-aware `ObserveAsEvents` collector
that uses `repeatOnLifecycle(STARTED)` — good). The concern is *scope*:

- A process-global event bus means any part of the app can emit an event any other part observes.
  Events aren't scoped to a navigation graph or screen, so reasoning about "who reacts to this" is
  global, and it's easy to get an event delivered to a screen that shouldn't care.
- Global singletons are hard to substitute in tests — you can't inject a fake.
- `SharedFlow` with no replay/buffer drops events emitted while nothing is collecting (e.g. during
  navigation transitions). That's sometimes desired, sometimes a silent-loss bug.

### The fix

Prefer Hilt-provided, lifecycle-scoped holders over global objects:

- Move app-wide events to an **activity-scoped `@HiltViewModel`** (or a `@Singleton` injected via
  Hilt rather than a top-level `object`) so they can be injected and faked in tests.
- For screen-local one-shot events (snackbars, navigation triggers), prefer a **per-ViewModel
  `Channel`/`SharedFlow`** exposed as the screen's events, rather than a global controller. This
  scopes the event to the screen that owns it.
- Decide replay semantics deliberately (a `Channel` with `BufferOverflow` or a small replay) so
  events emitted mid-transition aren't silently lost.

### Effort / risk

Low–medium. Can be done incrementally; the global `EventBus` can keep existing for truly global
concerns (force-logout, no-internet) while screen-local events migrate to per-ViewModel flows.

---

## P3 — 4. `BaseScreen` manual lifecycle bridge

### Evidence

`app/src/main/java/.../presentation/base/BaseScreen.kt` re-exposes Fragment-style
lifecycle callbacks to composables:

```kotlin
fun BaseScreen(
    onCreate: () -> Unit = {}, onStart: () -> Unit = {}, onResume: () -> Unit = {},
    onPause: () -> Unit = {},  onStop: () -> Unit = {},  onDestroy: () -> Unit = {},
    // …
) {
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_CREATE)  onCreate()
            if (event == Lifecycle.Event.ON_RESUME)  onResume()
            // …all six events bridged manually
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // …
}
```

### Why it matters

This was a *smart transition bridge* — it let screens keep Moxy/Fragment-era `onResume`/`onPause`
thinking while the UI moved to Compose, which is why it exists. But now that the migration is done,
it's a smell:

- It encourages **imperative lifecycle thinking** in a declarative framework. New screens copy the
  pattern and reach for `onResume {}` instead of the idiomatic `LaunchedEffect` /
  `collectAsStateWithLifecycle`.
- Six callbacks per screen invites **double-fire / ordering bugs** — the modernization history
  already shows a 2026 fix titled "add single source of lifecycle events in baseScreen."
- `BaseScreen` is doing a lot at once (scaffold + loading + global error dialog + lifecycle +
  internet checks + snackbar), making it a chokepoint that every screen depends on.

### The fix

Retire the lifecycle callbacks screen by screen in favor of idiomatic Compose:

- Replace `onResume { vm.refresh() }` with `LaunchedEffect(Unit) { vm.refresh() }` or a
  `lifecycle.repeatOnLifecycle` block where you genuinely need resume-scoped work.
- Collect state with `collectAsStateWithLifecycle()` instead of manual resume/pause gating.
- Once no screen passes the lifecycle lambdas, remove them from `BaseScreen`'s signature, shrinking
  it toward a pure layout scaffold.

Keep the genuinely cross-cutting parts of `BaseScreen` (scaffold, global error dialog, loading) —
just shed the imperative lifecycle surface.

### Effort / risk

Medium effort (touches many screens), low risk if done incrementally. Lowest priority — it's a
quality/idiom improvement, not a correctness or crash issue. Best tackled opportunistically as you
touch each screen for items #1–#2.

