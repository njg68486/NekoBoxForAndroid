package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.isVisible
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import libcore.Libcore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPlay
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.MessageStore
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.Util

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: BottomNavigationView
    private var currentMainFragment: ToolbarFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageStore.setCurrentActivity(this)
        val animateInitialControls = savedInstanceState == null

        binding = LayoutMainBinding.inflate(layoutInflater)

        // kl: 侧边抽屉整个下线，导航搬到底部四按钮
        navigation = binding.bottomNav
        navigation.setOnItemSelectedListener { item ->
            if (item.itemId == navigation.selectedItemId &&
                supportFragmentManager.findFragmentById(R.id.fragment_holder) != null
            ) {
                true
            } else {
                displayFragmentWithId(item.itemId)
            }
        }
        // 选中反馈交给 activeIndicator 那个圆角胶囊，去掉方形槽位 ripple
        navigation.itemRippleColor = null

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        } else {
            currentMainFragment =
                supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        }
        onBackPressedDispatcher.addCallback {
            if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        // kl dock：右侧 power 起停服务，左侧信息区点一下跑当前分组延迟测试（原型 dock-test）
        binding.dock.onPowerClick = {
            if (Libcore.versionBox() == "stub") {
                // stub 调试包核心是空壳，连 VPN 只会静默失败 —— 明说，不给假象
                snackbar(R.string.kl_core_not_ready).show()
            } else if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                connect.launch(null)
            }
        }
        binding.dock.onTestClick = {
            (currentMainFragment as? ConfigurationFragment)?.klRunLatencyTest()
        }

        setContentView(binding.root)

        // kl: fragment_holder 底部留白 = 底栏实际高度（wrap_content 的 nav 会随手势条
        // inset 变高，写死 margin 会出现内容压按钮或按钮压内容）
        binding.bottomNav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val lp = binding.fragmentHolder.layoutParams as? CoordinatorLayout.LayoutParams
            if (lp != null && lp.bottomMargin != binding.bottomNav.height) {
                lp.bottomMargin = binding.bottomNav.height
                binding.fragmentHolder.layoutParams = lp
            }
        }
        binding.bottomNav.post {
            val lp = binding.fragmentHolder.layoutParams as? CoordinatorLayout.LayoutParams
            if (lp != null && lp.bottomMargin != binding.bottomNav.height && binding.bottomNav.height > 0) {
                lp.bottomMargin = binding.bottomNav.height
                binding.fragmentHolder.layoutParams = lp
            }
        }

        currentMainFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
                ?: currentMainFragment
        if (!animateInitialControls) {
            syncMainControls(showWhenConnected = false, animate = false)
        }
        changeState(
            BaseService.State.Idle,
            animate = false,
            animateControls = animateInitialControls,
        )
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        MessageStore.setCurrentActivity(this)

        if (DataStore.hideFromRecentApps) {
            applyHideFromRecentApps(DataStore.hideFromRecentApps)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        val restoredFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        if (restoredFragment != null && restoredFragment !== currentMainFragment) {
            currentMainFragment = restoredFragment
            syncMainControls(
                fragment = restoredFragment,
                showWhenConnected = DataStore.serviceState == BaseService.State.Connected,
                animate = false,
            )
        }
    }

    fun applyHideFromRecentApps(hide: Boolean) {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = activityManager.appTasks
            if (tasks.isNotEmpty()) {
                val task = tasks[0]
                task.setExcludeFromRecents(hide)
            }
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }

    /** kl: dock 延迟行 —— 直接写文案（如「TCP: 测试中」） */
    fun klDockLatency(mode: String, text: String) {
        binding.dock.setLatency(mode, text)
    }

    /** kl: dock 延迟行 —— 从库里读当前选中节点的 ping；没有就显示 -- */
    fun klDockLatencyFromDb() {
        val mode = (currentMainFragment as? ConfigurationFragment)?.klTestModeTcp
        val modeText = when (mode) {
            false -> "HTTP"
            else -> "TCP"
        }
        runOnDefaultDispatcher {
            val profile = try {
                ProfileManager.getProfile(DataStore.selectedProxy)
            } catch (e: Exception) {
                null
            }
            val ping = profile?.ping?.takeIf { it > 0 }?.toString()?.plus("ms") ?: "--"
            runOnMainDispatcher { klDockLatency(modeText, ping) }
        }
    }

    fun refreshNavMenu(clashApi: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = clashApi
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://matsuridayo.github.io/nb4a-plugin/")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        currentMainFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        syncMainControls(fragment, showWhenConnected = false, animate = true)
    }

    /**
     * kl: dock 只在「配置」页出现（原型里 dock 就挂在首页节点列表末尾）。
     * showBottomBar 这个上游开关继续沿用：打开后所有页面都显示 dock。
     */
    private fun syncMainControls(
        fragment: Any? = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder),
        @Suppress("UNUSED_PARAMETER") showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        val showDock = fragment is ConfigurationFragment || DataStore.showBottomBar
        binding.dock.isVisible = showDock
        if (showDock) binding.dock.changeState(DataStore.serviceState, animate)
    }

    private fun refreshConfigurationProfileState() {
        val fragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)
        (fragment as? ConfigurationFragment)?.refreshProfileState()
    }

    /** kl: dock 是固定位置的，列表滚动不再驱动底栏隐藏 */
    @Suppress("UNUSED_PARAMETER")
    fun driveBottomBar(scrollDy: Int) = Unit

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(KlSettingsHubFragment())
            // 以下四项不在底栏，从「设置」页二级进入
            R.id.nav_app_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> displayFragment(WebviewFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://matsuridayo.github.io/")
                return false
            }

            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        // 二级页（日志/工具/文档/关于/软件设置）不在底栏菜单里，findItem 会返回 null；
        // 此时把底栏高亮停在「设置」上，保持所属关系可见。
        val item = navigation.menu.findItem(id)
        if (item != null) {
            item.isChecked = true
        } else {
            navigation.menu.findItem(R.id.nav_settings)?.isChecked = true
        }
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
        animateControls: Boolean = animate,
    ) {
        DataStore.serviceState = state
        refreshConfigurationProfileState()

        binding.dock.changeState(state, animate)
        if (state == BaseService.State.Connected) {
            // 连上后延迟行显示当前节点 ping（没测过就 --）
            klDockLatencyFromDb()
        }
        syncMainControls(
            showWhenConnected = state == BaseService.State.Connected,
            animate = animateControls,
        )
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    /**
     * kl: 不再 anchor 到 FAB。dock 固定在右下，Snackbar 抬到底栏上方即可；
     * 撤销删除那条会被 KlSnackbar 进一步压成左半屏，正好和 dock 并排不重叠。
     */
    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            anchorView = binding.bottomNav
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.dock.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
    }

    override suspend fun cbTrafficUpdate(data: TrafficDataBatch) {
        ProfileManager.postUpdate(data.items)
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        refreshConfigurationProfileState()
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.SHOW_BOTTOM_BAR -> syncMainControls(
                showWhenConnected = DataStore.showBottomBar,
                animate = true,
            )
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyDown(keyCode, event)) return true

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
