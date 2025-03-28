package io.muun.apollo.lib;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {

    @Test
    public void useAppContext() {
        // Context of the app under test (unchanged from original)
        final Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Improved: Fail with a descriptive message if the package doesn't match
        assertEquals(
            "The app's package name should be 'io.muun.apollo.lib.test'. " +
            "If this changed, update the test or check for misconfiguration.",
            "io.muun.apollo.lib.test",
            appContext.getPackageName()
        );
    }
}