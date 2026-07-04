// Evidence snippet — Android modernization case study
// See: ../README.md  ("Design system before screens")
//
// Before migrating ~84 screens to Compose, I built a shared scaffold so every screen got
// consistent loading, global error handling, lifecycle hooks, and snackbar behavior for free.
// This is the leverage that turned a slow, per-screen migration into a fast, repeatable one.
//
// (Excerpt — trimmed for clarity. The lifecycle bridge below was a deliberate *transition* tool:
//  it let screens keep MVP/Fragment-era onResume/onPause thinking while the UI moved to Compose.
//  Now that the migration is done, I'd retire it toward idiomatic LaunchedEffect /
//  collectAsStateWithLifecycle — see docs/lessons-and-tradeoffs.md item #7.)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseScreen(
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    // Lifecycle hooks bridged into Compose for screens still thinking in Fragment terms:
    onCreate: () -> Unit = {},
    onStart: () -> Unit = {},
    onResume: () -> Unit = {},
    onPause: () -> Unit = {},
    onStop: () -> Unit = {},
    onDestroy: () -> Unit = {},
    handleEvents: @Composable (setErrorType: (error: ErrorType) -> Unit) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    var hasInternet by remember { mutableStateOf(false) }
    var errorType by remember { mutableStateOf(ErrorType.NONE) }

    LaunchedEffect(Unit) { hasInternet = context.isNetworkAvailable() }
    LaunchedEffect(hasInternet) { if (!hasInternet) errorType = ErrorType.NO_INTERNET }

    // Single global error/event channel observed per screen (see snippets note on EventBus)
    ObserveAsEvents(EventBus.events) {
        when (it) {
            is GlobalEvents.UpdateErrorType -> if (hasInternet) errorType = it.type
            else -> {}
        }
    }

    // Bridge Android lifecycle -> the screen's callbacks
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> onCreate()
                Lifecycle.Event.ON_START -> onStart()
                Lifecycle.Event.ON_RESUME -> onResume()
                Lifecycle.Event.ON_PAUSE -> onPause()
                Lifecycle.Event.ON_STOP -> onStop()
                Lifecycle.Event.ON_DESTROY -> onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    handleEvents { errorType = it }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = topBar,
        bottomBar = { if (!isLoading) bottomBar() },
        floatingActionButton = { if (!isLoading) floatingActionButton() },
    ) { padding ->
        Box(modifier = modifier.padding(padding).consumeWindowInsets(padding)) {
            if (isLoading) LoadingAnimation() else content()
            if (errorType != ErrorType.NONE) {
                GlobalErrorDialog(errorType = errorType, onDismiss = { errorType = ErrorType.NONE })
            }
        }
    }
}
