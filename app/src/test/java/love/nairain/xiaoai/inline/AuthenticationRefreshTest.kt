package love.nairain.xiaoai.inline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationRefreshTest {
    private val login = AutofillEditor("example.login", 1, 129)
    private val vault = AutofillEditor("com.x8bit.bitwarden", 2, 129)

    @Test fun backFromUnlockedVaultRefreshesOriginalEditorOnlyOnce() {
        val refresh = AuthenticationRefresh()
        refresh.clicked(login, 100)
        refresh.left()
        refresh.startInput(vault)
        assertFalse(refresh.consume(vault, 200))
        refresh.startInput(login)
        assertTrue(refresh.consume(login, 300))
        assertFalse(refresh.consume(login, 301))
    }

    @Test fun clickBeforeLeavingMustNotRequestAutofillInsideAuthentication() {
        val refresh = AuthenticationRefresh()
        refresh.clicked(login, 100)
        assertFalse(refresh.needsRefresh(login, 101))
        assertFalse(refresh.consume(login, 101))
    }

    @Test fun authenticationWithoutVaultKeyboardStillRefreshesOnReturn() {
        val refresh = AuthenticationRefresh()
        refresh.clicked(login, 100)
        refresh.left()
        assertTrue(refresh.consume(login, 200))
    }

    @Test fun successfulCredentialResponseCancelsRefresh() {
        val refresh = AuthenticationRefresh()
        refresh.clicked(login, 100)
        refresh.startInput(vault)
        refresh.receivedCredentials(login)
        assertFalse(refresh.consume(login, 200))
    }

    @Test fun vaultSuggestionsDoNotCancelRefreshForOriginalEditor() {
        val refresh = AuthenticationRefresh()
        refresh.clicked(login, 100)
        refresh.startInput(vault)
        refresh.receivedCredentials(vault)
        assertTrue(refresh.consume(login, 200))
    }

    @Test fun differentFieldInOriginalAppDiscardsPendingRefresh() {
        val refresh = AuthenticationRefresh()
        refresh.clicked(login, 100)
        refresh.startInput(vault)
        refresh.startInput(login.copy(fieldId = 3))
        assertFalse(refresh.consume(login.copy(fieldId = 3), 200))
        assertFalse(refresh.consume(login, 300))
    }

    @Test fun disablingDismissalOrReloadCancelsRefresh() {
        val refresh = AuthenticationRefresh()
        refresh.clicked(login, 100)
        refresh.left()
        refresh.cancel()
        assertFalse(refresh.consume(login, 200))
    }

    @Test fun expiredAuthenticationDoesNotRefreshOnAnUnrelatedLaterVisit() {
        val refresh = AuthenticationRefresh()
        refresh.clicked(login, 100)
        refresh.left()
        assertFalse(refresh.consume(login, 100 + 5 * 60 * 1000L + 1))
        assertFalse(refresh.consume(login, 200))
    }

    @Test fun newActionClickReplacesPreviousOrigin() {
        val refresh = AuthenticationRefresh()
        refresh.clicked(login, 100)
        refresh.left()
        refresh.clicked(vault, 200)
        assertFalse(refresh.consume(login, 300))
        refresh.left()
        assertTrue(refresh.consume(vault, 400))
    }
}
