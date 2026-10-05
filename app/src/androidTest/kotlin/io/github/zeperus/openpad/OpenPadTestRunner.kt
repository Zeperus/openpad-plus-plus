package io.github.zeperus.openpad

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.util.concurrent.atomic.AtomicBoolean

/** Remembers whether any activity has been created in this process, so cold-start tests can prove they are cold. */
object ProcessHistory {
    val activityCreated = AtomicBoolean(false)
}

class OpenPadTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                ProcessHistory.activityCreated.set(true)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
