# A real trade-off: passing arguments through Compose Navigation

> Part of the [Android Modernization case study](../README.md). This is the kind of decision I like
> to talk through in interviews — a pragmatic choice that unblocked a big migration, the cost it
> carries, and how I'd evolve it.

## The problem

Compose Navigation routes are **strings**. When I migrated the whole app to Compose Navigation, I
needed to carry rich domain objects (`ActivityData`, `BasePlayModel`, lists of students) between
screens. The fastest path to unblock the migration was to serialize objects into the route.

## What I shipped (the pragmatic choice)

```kotlin
// Sender: serialize the object to JSON, URL-encode it, embed it in the route
fun NavController.navigateToTrainStats(params: ActivityData) {
    val json = Uri.encode(Gson().toJson(params))
    navigate("${Screen.TRAIN_STATS.name}/$json")
}

// Receiver: decode it back, with an empty-object fallback
val activity = backStackEntry.arguments
    ?.getString(ACTIVITY_ITEM)
    .jsonToObject(ActivityData::class.java, ActivityData.empty())
```

```kotlin
fun <T> String?.jsonToObject(classOfT: Class<T>, default: T): T {
    if (this == null || this == "null") return default
    return try {
        Gson().fromJson(Uri.decode(this), classOfT)
    } catch (e: Exception) {
        FirebaseCrashlytics.getInstance().recordException(e) // <-- the honest tell
        default
    }
}
```

It works, and it let me migrate every screen to Compose Navigation without a redesign. That was the
right call *at the time* to keep the app shipping.

## Why it's debt (and how I know)

- **It fails in production.** Every decode is wrapped in try/catch that reports to Crashlytics and
  falls back to an *empty object* — so a user can tap through and silently land on a blank screen.
  Silent data loss is worse than a crash because it's invisible in dashboards.
- **Route-length limits.** Large objects/lists risk truncation.
- **Stringly-typed.** The typed `AppNavigator` interface makes it *look* type-safe; underneath it's
  JSON in a URL.
- **It fights R8.** Reflective Gson (de)serialization is what forces ProGuard keep rules and was a
  source of release-only crashes.

## How I'd evolve it

**Preferred — pass IDs, re-fetch in the destination ViewModel.** The clean data layer already exists
for this; it also keeps data fresh instead of carrying a stale snapshot through the backstack.

```kotlin
// Route: "train_stats/{activityId}"
@HiltViewModel
class TrainStatsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getActivity: GetActivityUseCase,
) : ViewModel() {
    private val activityId: Long = checkNotNull(savedStateHandle["activityId"])
    val uiState = getActivity(activityId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)
}
```

**When the object genuinely must travel — type-safe navigation** (Navigation Compose 2.8+, which the
app is already on), backed by kotlinx.serialization:

```kotlin
@Serializable
data class EditPostRoute(val activityId: Long, val forTrainStats: Boolean, val openedNoteId: Long)

composable<EditPostRoute> { entry -> EditPostScreen(entry.toRoute()) } // typed, no Gson, no Uri.encode
navController.navigate(EditPostRoute(activityId = 42, forTrainStats = true, openedNoteId = 7))
```

Because the `AppNavigator` interface already fronts navigation, this swap happens behind the
interface — call sites don't change. That's the payoff of having abstracted the boundary.

— Full register: [`docs/lessons-and-tradeoffs.md`](../docs/lessons-and-tradeoffs.md) item #1.
