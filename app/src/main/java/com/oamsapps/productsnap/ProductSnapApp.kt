package com.oamsapps.productsnap

import android.app.Application
import com.oamsapps.productsnap.billing.Billing
import com.oamsapps.productsnap.billing.FreeAllowance

class ProductSnapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Billing.init(this)
        FreeAllowance.init(this)
    }
}
