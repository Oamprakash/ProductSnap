package com.oamsapps.productsnap

import android.app.Application
import com.oamsapps.productsnap.billing.Billing

class ProductSnapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Billing.init(this)
    }
}
