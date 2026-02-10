package com.tgstorage.ui.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tgstorage.ui.components.ScreenStub

@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
) {
    // Phase 2 will implement the full 3-step onboarding
    ScreenStub(
        title = "Onboarding",
        modifier = Modifier.fillMaxSize(),
    )
}
