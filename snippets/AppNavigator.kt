// Evidence snippet — Android modernization case study
// See: ../README.md  ("Abstract the framework at the boundary")
//
// Navigation sits behind a typed boundary: screens and ViewModels depend on small, per-feature
// navigator interfaces — never on the raw NavController. Each feature owns its navigation surface
// (PlayNavigator, PostNavigator, LessonsNavigator, …), all extending a shared CoreNavigator that
// keeps the app-wide backstack/auth policy in ONE place. AppNavigator composes them, and a single
// NavControllerAppNavigator implements the whole thing over the real NavController. Destinations are
// expressed as intent (navigateToObjectiveDetails) rather than route-string construction scattered
// across the UI — which also makes navigation trivially mockable in ViewModel tests and the nav
// library swappable without touching call sites.
//
// Trade-off I'm transparent about: rich arguments are still serialized as JSON inside route strings
// under the hood (see snippets/navigation-tradeoff.md). This boundary is precisely the seam through
// which type-safe routes can be introduced without changing a single caller.

// Shared core — the backstack/auth policy every feature needs.
interface CoreNavigator {
    fun navigate(route: String)
    fun navigateAndClearStack(route: String)      // login/logout stack rules live here, once
    fun navigateAndPopCurrent(route: String)
    fun popBackStack(): Boolean
    fun popBackStack(route: String, inclusive: Boolean): Boolean
    fun popBy(popCount: Int)
    fun handleDeepLink(intent: Intent?): Boolean
}

// One interface per feature — a screen depends only on the destinations it actually uses.
interface PlayNavigator : CoreNavigator {
    fun navigateToCourseSelection(basePlay: BasePlayModel, isAlreadyPlayed: Boolean, shouldPopCurrent: Boolean = false)
    fun navigateToScoreboardPlay(basePlay: BasePlayModel, holesInfo: MenScorecardList?)
    fun navigateToReviewScorePlay(activityData: ActivityData?, basePlay: BasePlayModel?, canShowPost: Boolean, actionType: String)
    // …
}
// …and PostNavigator, CurriculumNavigator, LessonsNavigator, EventsNavigator, SocialNavigator,
//    GoalsNavigator, NotesNavigator, ProgramsNavigator — one per feature, each extending CoreNavigator.

// The umbrella simply composes the per-feature interfaces.
interface AppNavigator :
    CoreNavigator,
    PlayNavigator,
    PostNavigator,
    CurriculumNavigator,
    LessonsNavigator,
    EventsNavigator,
    SocialNavigator,
    GoalsNavigator,
    NotesNavigator,
    ProgramsNavigator

// A single implementation wraps the real NavHostController.
class NavControllerAppNavigator(
    private val navController: NavHostController,
) : AppNavigator {
    override fun navigate(route: String) = navController.navigate(route)
    override fun navigateAndClearStack(route: String) = navController.navigateAndClearStack(route)
    override fun navigateToScoreboardPlay(basePlay: BasePlayModel, holesInfo: MenScorecardList?) =
        navController.navigateToScoreboardPlay(basePlay, holesInfo)
    // …
}

// AppNavHost is a thin assembler over per-feature NavGraphBuilder files — each feature's destinations
// live in their own file, so the host reads as a table of contents:
//   NavHost(navController, startDestination) {
//       authDestinations(navigator);   postDestinations(navigator);   playDestinations(navigator)
//       drillsDestinations(navigator); lessonsDestinations(navigator) /* … one call per feature */
//   }
