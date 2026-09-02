package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean

class VMessSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = VMessBean().apply {
        when {
            intent?.getBooleanExtra("vless", false) == true -> alterId = -1
            intent?.getBooleanExtra("x365", false) == true -> alterId = -2
        }
        initializeDefaultValues()
    }

}
