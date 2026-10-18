package com.oamsapps.productsnap

import android.app.Application
import com.oamsapps.productsnap.billing.Billing
import com.oamsapps.productsnap.billing.FreeAllowance
import com.oamsapps.productsnap.home.SellerPrefs
import com.oamsapps.productsnap.telemetry.Telemetry

class ProductSnapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.init(this)
        Billing.init(this)
        FreeAllowance.init(this)
        SellerPrefs.init(this)
    }
}
