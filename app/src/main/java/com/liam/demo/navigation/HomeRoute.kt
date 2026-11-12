package com.liam.demo.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import secure.SecureViewModel
import secure.ui.SecureScreen

@Serializable
sealed interface HomeRoute : NavKey {
    @Serializable
    data object Menu : HomeRoute

    @Serializable
    data object Secure : HomeRoute
}

fun EntryProviderScope<NavKey>.homeEntries(backStack: NavBackStack<NavKey>) {
    entry<HomeRoute.Menu> {
        MainScreenMenu(onOpenSecure = { backStack.add(HomeRoute.Secure) })
    }
    entry<HomeRoute.Secure> {
        SecureScreen(viewModel = viewModel<SecureViewModel>())
    }
}


