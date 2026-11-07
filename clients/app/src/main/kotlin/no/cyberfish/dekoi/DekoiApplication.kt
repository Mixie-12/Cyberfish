package no.cyberfish.dekoi

import android.app.Application

class DekoiApplication : Application() {
    val backend: Backend by lazy { Backend(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) }
}
