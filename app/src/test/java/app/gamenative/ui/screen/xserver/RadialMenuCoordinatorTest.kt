package app.gamenative.ui.screen.xserver

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import app.gamenative.PluviaApp
import com.winlator.container.Container
import com.winlator.xserver.XServer
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RadialMenuCoordinatorTest {
    @Test
    fun workerThreadDetach_removesTheOverlayOnTheMainLooper() {
        val context = RuntimeEnvironment.getApplication()
        var removalLooper: Looper? = null
        val host = object : FrameLayout(context) {
            override fun removeView(view: View) {
                removalLooper = Looper.myLooper()
                super.removeView(view)
            }
        }
        val coordinator = RadialMenuCoordinator(
            context, host, host, mock<Container>(), mock<XServer>(), { "WoW" }, { _, _ -> }, {}, {},
        )
        val failure = AtomicReference<Throwable?>()

        val worker = Thread {
            runCatching { coordinator.detach() }.onFailure { failure.set(it) }
        }
        worker.start()
        worker.join(5000)

        assertTrue("Worker must return without waiting for the UI looper", !worker.isAlive)
        assertNull(failure.get())
        assertEquals(1, host.childCount)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, host.childCount)
        assertSame(Looper.getMainLooper(), removalLooper)
    }

    @Test
    fun mainThreadCleanup_runsBeforeReturning() {
        var completed = false
        PluviaApp.runOnMainThread { completed = true }
        assertTrue(completed)
    }
}
