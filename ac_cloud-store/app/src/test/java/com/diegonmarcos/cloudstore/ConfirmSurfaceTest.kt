package com.diegonmarcos.cloudstore

import com.diegonmarcos.superapp.updater.ConfirmSurface
import com.diegonmarcos.superapp.updater.PackageInstallerReceiver
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1483 One prompt per user action: the system install confirmation opens as a dialog only for a session the user started while the
 * app is on screen. A session an unattended pass committed (Store's store_refresh chain at launch, the periodic worker) waits on its
 * notification and the row's "waiting for confirmation" status, foreground or not - the nav walk saw 36 dialogs over the Store.
 */
class ConfirmSurfaceTest {
    @Test fun aUserStartedInstallOnScreenShowsTheDialog() {
        assertEquals(ConfirmSurface.DIALOG, PackageInstallerReceiver.confirmSurface(unattended = false, foreground = true))
    }

    @Test fun anUnattendedPassNeverOpensTheDialogEvenOnScreen() {
        assertEquals(ConfirmSurface.NOTIFICATION, PackageInstallerReceiver.confirmSurface(unattended = true, foreground = true))
    }

    @Test fun anyBackgroundSessionWaitsOnTheNotification() {
        assertEquals(ConfirmSurface.NOTIFICATION, PackageInstallerReceiver.confirmSurface(unattended = false, foreground = false))
        assertEquals(ConfirmSurface.NOTIFICATION, PackageInstallerReceiver.confirmSurface(unattended = true, foreground = false))
    }
}
