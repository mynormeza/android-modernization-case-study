> Part of the [Android Modernization case study](../README.md).

# Android Modernization Guide — a Production App, 2018 → 2026

> A field guide reconstructed from the real git history of this project (2018 → 2026).
> It documents *how* the app was dragged from a Kotlin-synthetics, Java-style, MVP/RxJava
> codebase into a modern **Jetpack Compose + Compose Navigation + Coroutines + Hilt +
> Clean Architecture** app — phase by phase, with the order of operations, the rationale,
> and the concrete traps that cost time. Use it as a playbook the next time you face the
> same migration on another project.
>
> **Companion document:** for the debt that remains in the *current* code and a prioritized
> paydown plan, see [`lessons-and-tradeoffs.md`](./lessons-and-tradeoffs.md).

---

## 0. How to read this guide

Each migration below is written as a self-contained chapter with the same shape:

- **When / where** — the period it happened and the relevant commits.
- **Starting point** — what the code looked like before.
- **Target** — what "done" looks like.
- **Strategy** — the order the work was actually done in (this is the valuable part).
- **Challenges & gotchas** — what broke, what was non-obvious.
- **Lessons** — what to do differently / keep doing.

The golden rule that runs through *every* phase below:

> **Migrate incrementally, keep the app shippable at every commit, and let the old and new
> systems coexist until the old one can be deleted in a single, clean commit.**

This project never did a "big bang" rewrite. View Binding and synthetics coexisted.
Compose and the Fragment/XML world coexisted (`buildFeatures { viewBinding = true; compose = true }`
is still set in `app/build.gradle.kts` today). RxJava and Coroutines coexisted during the
swap. That coexistence is what made a multi-year, single-developer-paced modernization possible
without freezing feature work.

---

## 1. The end state (where this all landed)

Knowing the destination makes the journey legible. As of mid-2026 the app is:

**Build & tooling**
- **Kotlin 2.3.x**, **AGP 9.2.x**, **Gradle** with the daemon, `compileSdk = 36`, `minSdk = 30`, **JVM 17**.
- **Kotlin DSL** build scripts (`*.gradle.kts`), a **Version Catalog** (`gradle/libs.versions.toml`), and **type-safe project accessors** (`enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`).
- **KSP** instead of kapt; **R8** minification + resource shrinking on `release`/`distro`.
- **Compose compiler** plugin with strong skipping mode enabled.

**Architecture**
- Multi-module **Clean Architecture**: `:app` (presentation) → `:domain` (use cases / models) → `:data-repository` (network/repos) + `:data-local` (Room/db).
- **Hilt** for DI across all modules.
- **MVVM**: every screen is a `@Composable` + a Hilt `ViewModel` exposing UI state; no MVP, no Moxy.
- **Coroutines + Flow** end to end; zero RxJava.
- **Jetpack Compose** UI (≈84 `*Screen.kt` files; only ~6 `*Fragment.kt` remain as thin hosts/edge cases).
- **Compose Navigation** organized into per-feature graph builders behind a thin `AppNavHost`, fronted by per-feature navigator interfaces (composed into one `AppNavigator`) so screens never touch a raw `NavController`.

**Key libraries**: Retrofit 3 + OkHttp 5, Room 2.8, Coroutines 1.11, Compose Navigation 2.9,
Hilt 2.59, Arrow 2.x (functional error handling), Lottie-Compose, Maps-Compose, a Compose
rich-text editor, SQLCipher, Firebase (Analytics/Crashlytics/Perf/Messaging), Pendo.

---

## 2. Timeline at a glance

| Period | Phase | Outcome |
|---|---|---|
| 2018–2019 | Foundation | Fragment + bottom-nav app, custom navigation + Toolbar Manager |
| 2019-04 | **AndroidX migration** | Support libs → AndroidX |
| 2019-05 | RxJava → **RxJava2** | Reactive baseline modernized |
| 2021-03 | **Kotlin synthetics → View Binding** | `kotlin-android-extensions` plugin removed |
| 2021 | Dependency hygiene | Kotlin upgrade, ViewPager→ViewPager2, Room/Glide upgrades, mavenCentral, okhttp/Firebase deprecations |
| 2022-03 | Pure Dagger → **Hilt** | DI modernized |
| 2022-04→07 | RxJava → **Coroutines/Flow** | RxJava fully removed |
| 2022-11 | Groovy → **Kotlin DSL** | Build scripts in Kotlin |
| 2022-11+ | **Module extraction** | `:domain`, `:data-repository`, `:data-local` (Clean Arch) |
| 2023-04 | **AGP 8** | Toolchain bump |
| 2023-10→11 | Moxy MVP → **ViewModel (MVVM)** | Moxy removed, R8 full mode |
| 2023-10+ | **First Compose screens** | Leaderboard, curriculum progress |
| 2024-02 | **Compose Theme + design system** | Reusable components, theming |
| 2024–2025 | **Screen-by-screen Compose migration** | ~all feature flows moved to Compose |
| 2024-06 | **Version Catalog + KSP** | Dependency mgmt + faster annotation processing |
| 2025-05 | **Full Compose Navigation** | per-feature nav graphs, deep links, backstack |
| 2025 | **Compose dialogs & bottom sheets** | Remaining XML dialogs replaced |
| 2026-01 | **AGP 9** + Gradle daemon | Latest toolchain |
| 2026-05→06 | **Navigation wrapper (`AppNavigator`)** | Per-feature navigator interfaces; screens decoupled from `NavController` |

---

## 3. Phase 1 — AndroidX & reactive baseline (2018–2019)

**When:** AndroidX migration `2019-04`; RxJava → RxJava2 `2019-05`; navigation rewrite + Toolbar Manager `2019-07`.

**Starting point:** A young app on the old `android.support.*` libraries, fragment-based with
a bottom navigation bar, hand-rolled navigation between fragments.

**Target:** Get onto AndroidX (a hard prerequisite for *everything* that came later — Jetpack,
modern Compose, current AGP all assume AndroidX) and modernize the reactive layer.

**Strategy:**
1. Run the AndroidX migration (package rewrite `android.support.*` → `androidx.*`) as one
   focused change, then fix the long tail of compile errors.
2. Update RxJava → RxJava2 separately so the two large changes don't entangle.
3. Rewrite the home/navigation flow and introduce a central **Toolbar Manager** to stop
   duplicating toolbar setup in every screen.

**Challenges:**
- AndroidX is all-or-nothing at the package level — `android.enableJetifier=true` was needed
  to keep third-party libs that still referenced support libraries working during the transition.
- A custom navigation layer was built here. It paid off short-term but became the very thing
  Compose Navigation had to replace 6 years later — see Phase 11.

**Lesson:** Do the AndroidX migration *first and alone*. Nothing modern installs cleanly on
support libraries, and mixing it with other changes makes the compile-error storm impossible to triage.

---

## 4. Phase 2 — Kotlin synthetics → View Binding (2021-03)

This is where the deliberate modernization campaign began.

**Commits:** a tight batch over a few days, merged via `migrate/to_view_binding`. Capstone
commit: *"Finish view binding migration of all pending view in the app and remove synthetic
plugin."*

**Starting point:** `kotlin-android-extensions` synthetic imports
(`import kotlinx.android.synthetic.main.fragment_home.*`) used everywhere. These are unsafe
(no null/cross-layout checking, runtime crashes when a view ID exists in a different layout),
and the plugin was deprecated and slated for removal — a blocker for newer Kotlin/AGP.

**Target:** Every Activity, Fragment, and RecyclerView ViewHolder uses generated **View Binding**
classes; the synthetic plugin is deleted from the build.

**Strategy (the order that worked):**
1. Enable `buildFeatures { viewBinding = true }` *without* removing synthetics — they coexist.
2. Migrate in **vertical slices by screen group**, a handful of fragments per commit. The
   commit messages literally enumerate the batches:
   - the scheduling + play-flow fragments
   - the roster/goals/sessions group
   - the profile/programs/social group
   - the "last ones" cleanup batch (`BaseFragment`, `WebViewFragment`, auth fragments, …)
3. Migrate **Activities**, then the **one straggler fragment**.
4. Migrate **RecyclerView ViewHolders** — pass the binding reference into the holder
   ("pass view binding reference to view holder").
5. **Only when nothing references a synthetic**, delete the `kotlin-android-extensions` plugin
   in a single commit. This is the point of no return and must be last.

**Challenges & gotchas:**
- **Cross-layout ID collisions.** Synthetics silently bound whatever view had the matching ID;
  View Binding is layout-scoped, surfacing latent bugs — "fix view binding referencing wrong
  view." Expect a round of these.
- **ViewHolder lifecycle / memory.** Binding references held in adapters/fragments leak if not
  cleared. The project explicitly added "some pagination unbinding to help with memory
  issues" — null out the binding in `Fragment.onDestroyView()` and avoid holding
  bindings past the view's lifecycle.
- A follow-up `fix/viewbinding_crashes` branch was needed after release — budget for a
  stabilization pass after a sweeping mechanical migration.

**Lesson:** Mechanical, repetitive migrations are best done in small enumerated batches with the
old and new mechanism coexisting, deleting the old plugin *dead last*. Writing the migrated file
list into each commit message made progress auditable and made the "what's left" question trivial.

---

## 5. Phase 3 — Dependency hygiene & deprecation paydown (2021, ongoing)

Modernization isn't only big rewrites; a continuous stream of smaller upgrades kept the codebase
on supported ground and unblocked the big moves.

**Representative commits (2021):**
- `upgrade kotlin version, some dependencies and code clean up`
- `complete migration from view pager to view pager 2` (and ViewPager2 for profile tabs, `2021-09/10`)
- `Upgrade room lib to latest version, fix breaking changes for room upgrade`
- `upgrade glide and fix breaking changes`
- `Maintenance: Upgrade various deps; Migrate some deprecated methods from okhttp; Migrate
  Firebase instance id to installation`
- `upgrade dependencies including some that were moved to mavenCentral`
- `remove depracated location lib and upgrade to new one`

**Strategy:**
- Treat dependency upgrades as **their own branches/PRs** (`maintenance/*`, `upgrade/*`), never
  bundled with feature work, so a regression bisects to a single library.
- When a repo (jcenter) sunset, migrate coordinates to **mavenCentral** proactively.
- Replace deprecated APIs as you touch them (okhttp interceptors, Firebase Instance ID →
  Installations) rather than letting them rot.
- ViewPager → **ViewPager2** required reworking the adapter API and fixing a migration crash
  (`fix crash caused by migration to viewpager2`) — a small but representative "breaking
  upgrade" pattern.

**Lesson:** Continuous, isolated dependency maintenance is the cheapest insurance. The big
migrations (Hilt, Compose, AGP 8/9) only installed cleanly because the surrounding libraries
were already current. A library two majors behind is a migration of its own.

---

## 6. Phase 4 — Pure Dagger → Hilt (2022-03)

**Commits:** `migrate from pure Dagger to hilt` via `maintenance/hilt_migration`.

**Starting point:** Hand-written Dagger 2 — components, modules, `@Subcomponent` graphs,
manual injection plumbing in Activities/Fragments.

**Target:** Hilt's standardized component hierarchy and Android entry-point integration.

**Strategy:**
1. Add Hilt plugin + deps; annotate the `Application` with `@HiltAndroidApp`.
2. Convert Dagger `@Module`s into Hilt modules with the correct `@InstallIn` scopes
   (`SingletonComponent`, `ViewModelComponent`, etc.).
3. Annotate Android entry points (`@AndroidEntryPoint`) and migrate field/constructor injection.
4. Delete the bespoke Dagger components once nothing references them.

**Challenges:**
- Mapping ad-hoc custom scopes onto Hilt's fixed component scopes is the main intellectual work
  — get the `@InstallIn` targets right or you get scope/lifetime mismatches at runtime.
- Hilt requires the annotation processor; this later motivated the **kapt → KSP** move (Phase 9).

**Lesson:** Hilt is mostly a *re-expression* of an existing Dagger graph, not a redesign. Do it
before introducing ViewModels widely so the new ViewModels can be `@HiltViewModel` from day one
(which is exactly the ordering this project used: Hilt 2022, ViewModel migration 2023).

---

## 7. Phase 5 — RxJava → Coroutines & Flow (2022-04 → 2022-07)

**Commits:** a clean package-by-package sweep under `migrate/coroutines`:
- `Migrate to coroutines all request/usecases used on ui/auth package`
- `… ui/coach package`
- `… ui/community and ui/curriculum`
- `… ui/family, ui/goals, ui/lessons, ui/main`
- `… ui/notes, ui/notifications, ui/play`
- `Finished migration to coroutines network request/usecases`
- later: `migrate parallel request to coroutines`, then `cleaun up: remove insntances of rxJava`

**Starting point:** RxJava2 `Observable`/`Single` chains for network and use cases, with
`CompositeDisposable` lifecycle management.

**Target:** `suspend` functions + `Flow`, scoped to `viewModelScope`/lifecycle; RxJava removed entirely.

**Strategy (note the discipline):**
1. Migrate **one UI package at a time**, each as its own commit. The repo network layer exposed
   both styles during the transition so screens could be flipped independently.
2. Convert use cases to `suspend`; convert streams to `Flow`. Replace `subscribe`/`Disposable`
   with `launch`/`collect` in `viewModelScope`.
3. Handle **parallel requests** explicitly (`async`/`awaitAll`) — this was called out as its own
   commit because naive sequential `await` regresses performance vs. RxJava's `zip`.
4. **Delete RxJava only after the last package was migrated** — the dedicated "remove instances
   of rxJava" cleanup commit.

**Challenges & gotchas:**
- **Parallelism regressions.** The biggest behavioral trap: RxJava `zip`/`merge` ran calls
  concurrently; a 1:1 rewrite to sequential `suspend` calls silently serializes them. Audit every
  multi-source screen and restore concurrency with `async`/`awaitAll`.
- **Error/lifecycle semantics differ.** RxJava `onError` terminates a stream; coroutines throw.
  Cancellation is cooperative and tied to scope. This is also when functional error handling with
  **Arrow** (`Either`) entered the use-case layer to make failures explicit instead of thrown.
- **Threading.** Replace `subscribeOn/observeOn` with the right `Dispatchers` + `withContext`;
  don't assume the old scheduler placement carried over.

**Lesson:** A "translate each call site" migration is correctness-complete but not
*performance*-complete. The non-obvious work is preserving concurrency and re-modeling error flows,
not the syntactic `Observable → suspend` swap.

---

## 8. Phase 6 — Groovy → Kotlin DSL (2022-11)

**Commits:** `migrate to kotlin dsl`, `final adjustments kotlin dsl migration` via `migrate/kotlin_dsl`.

**Starting point:** `build.gradle` (Groovy).

**Target:** `build.gradle.kts` for every module + `settings.gradle.kts`.

**Strategy:** Convert one script at a time (root, then each module), fixing the type-strictness
fallout — Groovy's dynamic `def`/string assignments become typed Kotlin (`= ` assignments,
`getByName`, typed `buildConfigField`).

**Challenges:**
- Kotlin DSL is strict: every property assignment is typed, methods like `getByName("debug")`
  replace Groovy's dynamic block access, and IDE sync is slower but gives real autocomplete.
- This step is the natural on-ramp to the **Version Catalog** (Phase 9) — do DSL first, catalog second.

**Lesson:** Worth it for autocomplete and compile-time validation of build logic, but it's a
prerequisite step, not a goal in itself — its real payoff is the catalog and type-safe accessors
that build on top of it.

---

## 9. Phase 7 — Modularization / Clean Architecture (2022-11 onward)

**Commits:** module extraction and cleanup — `mode drills repo to data-repo module and add unit
tests`, `remove references from data-repo module in app module`, `update some libs a move use
cases to domain module`, `clean up domain module`, plus a full unit-test build-out per module.

**Starting point:** A monolithic `:app` module mixing UI, repositories, models, and DB.

**Target:** Layered modules:
```
:app             → presentation (Compose UI, ViewModels, navigation)
   ↓ depends on
:domain          → use cases, domain models, repository interfaces
   ↑ implemented by
:data-repository → Retrofit services, repository implementations (network)
:data-local      → Room database, DAOs, local persistence
```
`settings.gradle.kts` today: `include(":app", ":data-repository", ":data-local", ":domain")`.

**Strategy:**
1. Extract `:domain` first — pure Kotlin models + use cases + repository *interfaces*, no Android deps.
2. Move repository *implementations* into `:data-repository`; the app depends on interfaces, not impls (DIP).
3. Split persistence into `:data-local` (Room/SQLCipher).
4. Wire it all through **Hilt** (already in place from Phase 4 — this is why Hilt came first).
5. Add **per-module unit tests** as each module gains a clean boundary (MockK, Turbine for Flow).
   In practice this only happened for the **data modules** (`:data-repository`, `:data-local`); the
   `:domain` use cases and `:app` ViewModels remained untested — a gap documented in
   [`lessons-and-tradeoffs.md`](./lessons-and-tradeoffs.md).

**Challenges:**
- Breaking circular dependencies — the `remove references from data-repo module in app module`
  commit is the telltale sign of untangling a layer that was reaching the wrong direction.
- Deciding what is "domain" vs "data" — models that carried persistence/serialization annotations
  had to be split into clean domain models + data DTOs.

**Lesson:** Modularize *after* DI is in place, and extract the dependency-free `:domain` core
first. Module boundaries are where unit testing finally becomes cheap — and the data modules took
advantage of it. The missed opportunity was *not* extending the same discipline leftward to
`:domain` and `:app` during the Rx→Coroutines and Moxy→ViewModel migrations, which is exactly where
a safety net would have caught the regressions that instead reached release.

---

## 10. Phase 8 — Moxy MVP → Android ViewModel / MVVM (2023-10 → 2023-11)

**Commits:** `migrate to view model screens in play flow`, `migrate create/edit post screens to
view model`, … , capstone `finish view model migration, remove moxy lib and enable R8 full mode`,
then `clean up Context references in viewModels` and `re-name clean up after view model migration`.

**Starting point:** **Moxy** MVP — `MvpView` interfaces, `@InjectViewState`, presenters with
view-state command queues.

**Target:** Jetpack `ViewModel` (`@HiltViewModel`) exposing observable UI state; this is the
bridge that makes Compose adoption natural (Compose reads state, ViewModel owns it).

**Strategy:**
1. Migrate flow by flow (play flow → post → remaining screens), each its own commit.
2. Replace presenter + MvpView with ViewModel + state holder; the Fragment observes state.
3. **Purge `Context` from ViewModels** (`clean up Context references in viewModels`) — a ViewModel
   outlives Activities/views, so holding `Context` leaks. Resources/strings get resolved in the UI layer.
4. Remove the Moxy dependency once the last presenter is gone.
5. Rename files to drop Moxy-era naming.

**Challenges & gotchas — R8 full mode:**
Enabling **R8 full mode** alongside the cleanup caused release crashes. The history shows the
real-world dance:
- `disable proguard` (early panic), then *"fix build crash and set R8 full mode to false"*,
  then *"fix crashes caused by R8 full mode"* and finally a `fix/r8_full_mode` PR.
- Root cause pattern: full mode is more aggressive about stripping/renaming; reflection-based libs
  (Gson/Retrofit model classes, Moxy remnants, Pendo) need explicit `-keep` rules. The fix is
  adding keep rules for serialized models and reflection entry points, **not** turning minification off.

**Lesson:** MVP→MVVM is the *pivotal* enabler for Compose — do it before going wide on Compose.
And never enable R8 full mode in the same breath as a big refactor without budgeting for a
keep-rules stabilization pass; test the **minified release build**, not just debug.

---

## 11. Phase 9 — Build modernization round 2: Version Catalog + KSP (2024-06)

**Commit:** `migrate to version catalog and KSP` via `migrate/kotlin_dsl` lineage.

**Starting point:** Hard-coded dependency strings across `*.gradle.kts`; **kapt** annotation processing.

**Target:** Centralized `gradle/libs.versions.toml` + **KSP** (Kotlin Symbol Processing) for
Hilt/Room/etc.

**Strategy:**
1. Move every version + library + plugin coordinate into `libs.versions.toml`; reference via
   `libs.*` and `alias(libs.plugins.*)`.
2. Enable `TYPESAFE_PROJECT_ACCESSORS` so modules are referenced as `projects.domain` instead of `project(":domain")`.
3. Swap kapt → KSP for every supported processor (Hilt, Room). KSP is multiples faster and Kotlin-native.

**Challenges:**
- KSP and kapt can't both process the same annotations cleanly — migrate all processors together
  and confirm each library's KSP support version.
- The catalog forces naming discipline; one-time churn to rename every dependency reference.

**Lesson:** The catalog pays for itself the moment you do a coordinated multi-library bump (every
later "update libs" commit became a one-file change). KSP cut build times and was a prerequisite
for staying current with Kotlin 2.x.

---

## 12. Phase 10 — Compose adoption: design system first, then screens (2023-10 → 2025)

This is the longest phase and the heart of the modernization. It was **not** done by converting
screens at random — there was a deliberate sequence.

### 12.1 Beachhead screens (2023-10 → 2023-11)
First Compose code landed on **isolated, self-contained screens** to learn the tooling with low
blast radius: the events leaderboard, then the curriculum-progress views (plus an event dialog
and deep links), then progress/level info cards. These are leaf screens with simple inputs —
ideal for de-risking.

### 12.2 The design system (2024-02) — the real unlock
**Commit:** `Add compose theme and some reusable compose components`.
Before going wide, a Compose **theme** (colors/typography/dimens bridged from the existing XML
resources — note `colorResource`/`dimensionResource` usage in `BaseScreen.kt`) and a library of
reusable components were built. Everything after this reused that foundation, which is why the
later migration accelerated.

A shared **`BaseScreen`** composable was introduced as the per-screen scaffold (see
`app/src/main/java/.../presentation/base/BaseScreen.kt`). It centralizes:
- Material3 `Scaffold` with `topBar`/`bottomBar`/FAB slots.
- A unified **loading** state.
- **Lifecycle callbacks** (`onCreate/onStart/onResume/...`) bridged into Compose via a
  `DisposableEffect` + `LifecycleEventObserver` — so ViewModels keep lifecycle hooks without Fragments.
- Global **error handling** (no internet / timeout / general) via an `EventBus` + `ObserveAsEvents`,
  rendering a single `GlobalErrorDialog`.
- A custom snackbar.

This "one base screen to rule them all" pattern is what kept hundreds of screens consistent.

### 12.3 Feature-by-feature screen migration (2024 → 2025)
With theme + `BaseScreen` in place, screens were migrated **feature flow by feature flow**, each a
PR. The actual order from history:
- Auth/login (`compose/login_screens`), home, select member, discover, more.
- Activity feed / post details / communities / community details / challenges.
- Profile, manage family, coach-specific screens, student roster.
- Programs (list, event view, schedule session), notifications.
- Lesson plans (full flow), goals (full flow), notes list, edit profile.
- Play flow (setup/selection, review, scoreboard, create/edit post) — done as "view only" first,
  then wired to logic.
- Drills flow (search, list, home, directions, details, history, full activity edition).
- The curriculum-progress graph screen (the last graph-heavy legacy Fragment) → Compose.

Pattern within each flow: build the Composable UI against the existing ViewModel state, keep the
Fragment as a thin `ComposeView` host, verify parity, then later drop the Fragment entirely.

### 12.4 Dialogs & bottom sheets (2025)
The last XML UI to fall. A dedicated campaign moved every dialog and bottom sheet to Compose:
basic info/action dialogs, under-age consent, child-safety tips, play-type, plan filter, plan
details, manage-enrollment bottom sheet, custom-course bottom sheet, curriculum notification
dialog. A reusable `BasicDialog` composable was added so dialogs stopped being one-offs.

### 12.5 Specialized component swaps (2025)
- Rich-text editor replaced with a **Compose rich-text library** (`richeditor-compose`) in the
  announcement screen.
- Maps moved to **maps-compose**; animations to **lottie-compose**.
- Mass-review objective grid rebuilt in Compose specifically to fix layout bugs that were
  intractable in the XML version (`Migrate mass review objectives grid to compose ... to fix layout issues`).

**Challenges & gotchas across Compose adoption:**
- **Interop both ways.** Fragments hosting `ComposeView`, and Compose screens needing to launch
  legacy Activities, had to coexist for ~2 years. Keep both `viewBinding = true` and `compose = true`.
- **Lifecycle.** Fragments gave `onResume`/`onPause` for free; Compose doesn't. The `BaseScreen`
  `DisposableEffect`/`LifecycleEventObserver` bridge was the fix. Later refined to a
  *single source of lifecycle events in baseScreen* (2026) to stop double-firing.
- **Recomposition performance.** Strong skipping mode was enabled (`composeCompiler {
  enableStrongSkippingMode = true }`); unstable params and lambda allocations cause needless
  recomposition — stabilize state and hoist callbacks.
- **Theming bridge.** Rather than re-author every color/dimension, the Compose theme reads the
  existing XML `colorResource`/`dimensionResource`, keeping a single source of truth during transition.
- **Edge-to-edge.** A dedicated `edge_to_edge` effort handled window insets (`WindowInsets(0,0,0,0)`
  + `consumeWindowInsets` in `BaseScreen`) — required once targeting newer SDKs.

**Lesson:** **Build the design system and base scaffold *before* migrating screens en masse.** The
2023 beachhead screens were slow (no shared components); everything after 2024-02 was fast because
theme + `BaseScreen` + reusable components existed. Migrate by user-facing flow, keep Fragments as
disposable hosts, and save dialogs/bottom-sheets for last (they're the most coupled to legacy APIs).

---

## 13. Phase 11 — Compose Navigation (2025-05)

**Commits:** the `compose/independent_composables_*` series, then `full_compose_navigation`,
capstone `Implement full compose navigation for all screens of the app` — which moved essentially
every screen onto Compose Navigation.

**Starting point:** The custom 2019-era navigation + Fragment transactions + a `NavController`
graph in XML.

**Target:** Compose Navigation for every screen — destinations organized into per-feature graph
builders behind a thin `AppNavHost`, with typed argument passing, deep links, and proper backstack
control.

**Strategy:**
1. First make screens **independent composables** (the `independent_composables` PRs) — decoupled
   from Fragment hosting so they could be dropped into a NavHost.
2. Add Compose Navigation **tab by tab / flow by flow**: auth + home tabs → communities →
   more/challenges → programs → home nested → drills flow → play flow → create/social → then the
   full integration commit.
3. Handle **deep links** in the NavHost (`handleDeepLink`), including the tricky case where two
   features share one link and the app must disambiguate at runtime.
4. Fix **backstack semantics** globally — "Fix backstack clearing in general ... no
   improper back navigation happens in log out" — define `navigateAndClearStack` /
   `navigateAndPopCurrent` semantics once.
5. Replace XML pickers with Compose equivalents pulled in by the nav rewrite (e.g.
   `Replace old date picker with new compose calendar`, fixing local-date handling).

**Challenges & gotchas:**
- **Typed argument passing — and the shortcut that became debt.** Compose Navigation routes are
  strings; passing rich domain objects (`ActivityData`, `BasePlayModel`, lists) safely is the hard
  part. This project solved it pragmatically by **serializing whole domain objects to JSON with
  Gson, `Uri.encode`-ing them, and embedding them in the route string**
  (`navigate("train_stats/$json")`), decoding on the other side with a `jsonToObject(..., empty())`
  fallback. It works and unblocked the migration, but it's a known smell: decode failures are caught
  and reported to Crashlytics (i.e. it fails in production and silently falls back to empty objects),
  large objects risk route-length limits, and the reflective Gson use fights R8. The typed
  `AppNavigator` interface (next phase) wraps this but does not fix it. **See
  [`lessons-and-tradeoffs.md`](./lessons-and-tradeoffs.md) item #1 for the recommended fix
  (type-safe routes / pass IDs and re-fetch).**
- **Backstack correctness on auth transitions.** Login/logout must clear the stack or back-button
  bugs and stale screens appear — fixed centrally rather than per-screen.
- **Deep-link ambiguity & post-login redirect.** Deep links arriving without a valid session had
  to defer to login then resume; shared links needed runtime disambiguation.
- **Navigation is global, so I migrated per flow.** Because every screen ultimately hangs off one
  graph, the work only stayed tractable by making screens host-independent first and adding
  navigation flow by flow — then keeping destinations in per-feature graph builders rather than one
  wall of routes.

**Lesson:** Make screens **host-independent first**, migrate navigation per flow, and centralize
backstack/auth rules. Don't try to encode complex arguments in route strings — put a typed API in
front of navigation (Phase 12).

---

## 14. Phase 12 — The Navigation Wrapper / `AppNavigator` (2026-05 → 2026-06)

**Commits:** `WIP - Added wrapper class and interface for app navigation`, `update all screens to
use nav wrapper instead of direct navcontroller`, `update missing screens to use navigation
wrapper instead of raw controller`, via `update/libs_upgrade`.

**Starting point:** Screens calling `navController.navigate(...)` directly — coupling every
composable to Compose Navigation internals and to route-string construction.

**Target:** Per-feature navigator interfaces over a shared `CoreNavigator`, composed into one
**`AppNavigator`** (`app/.../presentation/ui/AppNavigator.kt`) with a single implementation
**`NavControllerAppNavigator`** wrapping the real `NavController`.

What the boundary gives you (excerpt) — per-feature interfaces over a shared core:
```kotlin
interface CoreNavigator {
    fun navigate(route: String)
    fun navigateAndClearStack(route: String)   // login/logout stack rules, in one place
    fun navigateAndPopCurrent(route: String)
    fun popBackStack(): Boolean
    fun popBy(popCount: Int)
    fun handleDeepLink(intent: Intent?): Boolean
}

// One interface per feature — a screen depends only on the destinations it uses:
interface PlayNavigator : CoreNavigator {
    fun navigateToCourseSelection(basePlay: BasePlayModel, isAlreadyPlayed: Boolean, shouldPopCurrent: Boolean = false)
    fun navigateToScoreboardPlay(basePlay: BasePlayModel, holesInfo: MenScorecardList?)
    // …
}

// The umbrella composes them; one NavControllerAppNavigator implements the whole thing:
interface AppNavigator :
    CoreNavigator, PlayNavigator, PostNavigator, CurriculumNavigator, LessonsNavigator,
    EventsNavigator, SocialNavigator, GoalsNavigator, NotesNavigator, ProgramsNavigator
```

**Why this matters:**
- **Decoupling/testability.** ViewModels/screens depend on an interface, not a framework type —
  trivially mockable, and the navigation library could be swapped without touching call sites.
- **Intent at the boundary (but not true type safety).** Argument serialization into routes is
  centralized in one implementation; call sites express *intent* (`navigateToObjectiveDetails(...)`)
  not mechanics. Note the abstraction hides — but does not remove — the Gson-JSON-in-route mechanism
  underneath (see Phase 11 and the tech-debt doc); the interface is the right seam through which
  type-safe navigation can be swapped in without touching call sites.
- **Per-feature interface segregation.** Each feature exposes only its own destinations through a
  dedicated navigator (all sharing `CoreNavigator`), so a screen depends on the slice of navigation
  it uses — not on every other feature's surface.
- **Consistent backstack policy.** `navigateAndClearStack`/`popBy` etc. live in one place, so the
  auth/backstack rules fought for in Phase 11 can't be re-broken per screen.

**Strategy:** Define the interface + implementation, then migrate screens in batches off the raw
`NavController` (the "update all screens" → "update missing screens" commit pair is the familiar
"sweep then catch the stragglers" pattern).

**Lesson:** Once you're fully on a navigation framework, **put an abstraction in front of it.** It
converts navigation from scattered framework calls into a typed, testable, swappable API — and it's
the clean capstone that makes the whole presentation layer framework-agnostic.

---

## 15. Phase 13 — Toolchain endgame: AGP 8 → AGP 9, Kotlin 2.x (2023-04, 2026-01+)

- **AGP 8** (`2023-04`): `upgrade gradle plugin to version 8` — namespace in DSL (drop
  `package` from manifest), non-transitive R classes, Java 17 toolchain.
- **AGP 9** (`2026-01`): `migrate to AGP 9`, then `migrate to gradlew daemon and update some libs`.
- **Kotlin 2.x + Compose Compiler plugin**: the Compose compiler became a standalone Kotlin plugin
  (`alias(libs.plugins.compose.compiler)`) — required when moving to Kotlin 2.0+.

**Challenges:** Each major AGP bump removes deprecated DSL and tightens defaults (resource
namespacing, manifest `package`, build-feature flags must be explicit:
`buildConfig = true`, `viewBinding = true`, `compose = true` are now all opt-in). Keep a running
`migrate some deprecated methods` habit so each bump is small.

**Lesson:** Stay at most one major AGP behind. The continuous deprecation-paydown commits made
the AGP 8 and 9 jumps routine instead of multi-week ordeals.

---

## 16. Cross-cutting principles (the transferable playbook)

1. **AndroidX / toolchain currency is the foundation.** Nothing modern installs on stale
   support libs or an AGP three majors behind. Pay this continuously.
2. **One concern per branch/PR.** `migrate/*`, `upgrade/*`, `maintenance/*`, `compose/*` —
   never bundle a library bump with a feature so regressions bisect cleanly.
3. **Coexistence over big-bang.** Enable the new mechanism alongside the old (View
   Binding + synthetics, Compose + View Binding, Coroutines + RxJava). Delete the old one in a
   single final commit *only when nothing references it*.
4. **Migrate in enumerated vertical slices.** By screen group, by UI package, by feature flow —
   and write the migrated items into the commit message so "what's left" is always answerable.
5. **Order matters — dependencies between migrations:**
   - AndroidX → everything.
   - Hilt → before ViewModels (so they're `@HiltViewModel` from birth).
   - ViewModel/MVVM → before wide Compose adoption (Compose needs state owners).
   - Kotlin DSL → before Version Catalog → which enables cheap mass library bumps.
   - Design system + `BaseScreen` → before screen-by-screen Compose.
   - Independent composables → before Compose Navigation.
   - Compose Navigation → before the `AppNavigator` abstraction.
6. **Build a base scaffold + design system before scaling a UI migration.** The single biggest
   accelerator in this whole history.
7. **Abstract the framework at the boundary.** `AppNavigator` is the model: depend on interfaces,
   keep the framework swappable and the code testable.
8. **Budget a stabilization pass after every sweeping change.** View Binding crashes, R8 keep
   rules, ViewPager2 crash, lifecycle double-fires — the cleanup commit is part of the migration,
   not a failure of it. Better still, pair the stabilization pass with tests *written before* the
   migration — the regressions that reached release here (View Binding crashes, R8 keep rules,
   parallelism loss) were all in the layers that had no tests.
9. **Test the *release* build.** R8 full mode only bites in minified builds; reflection-based libs
   need keep rules. Debug-only verification hides the worst class of regressions.
10. **Watch for silent behavioral regressions, not just compile errors.** The Rx→Coroutines
    parallelism loss is the canonical example: it compiles, it "works," and it's slower. Re-derive
    *semantics* (concurrency, error handling, cancellation, backstack), not just syntax.

---

## 17. Migration order template (reuse this on the next legacy app)

```
0.  Get on AndroidX + a current-ish AGP/Kotlin/Gradle baseline.
1.  Kill unsafe view access (synthetics) → View Binding.       [mechanical, batched]
2.  Continuous dependency hygiene (its own PRs, mavenCentral). [ongoing forever]
3.  DI: hand-rolled Dagger → Hilt.                             [before ViewModels]
4.  Reactive: RxJava → Coroutines/Flow (+ Arrow for errors).  [package by package]
5.  Build scripts: Groovy → Kotlin DSL.                        [on-ramp to #7]
6.  Modularize: extract :domain, then :data-*; wire via Hilt.  [DIP + unit tests]
7.  Dependency mgmt: Version Catalog + KSP (drop kapt).        [enables cheap bumps]
8.  Presentation: MVP/Moxy → Jetpack ViewModel (MVVM).         [Compose enabler]
9.  Compose: theme + BaseScreen + design system FIRST,         [the real unlock]
    then migrate screens flow-by-flow, dialogs/sheets LAST.
10. Make screens host-independent → Compose Navigation         [per flow, central backstack]
    (NavHost, typed args, deep links).
11. Put an AppNavigator abstraction in front of navigation.    [decouple + testable capstone]
12. Ride the toolchain: AGP 8 → 9, Kotlin 2.x, R8 full mode    [continuous, test release builds]
    (+ keep rules), edge-to-edge, strong skipping.
```

---

*Reconstructed from the project's git history (2018-06 → 2026-06). Commit hashes, PR numbers,
and other repository identifiers have been redacted for client confidentiality; the quoted
commit messages are paraphrased anchors into a private history I can walk through live.*
