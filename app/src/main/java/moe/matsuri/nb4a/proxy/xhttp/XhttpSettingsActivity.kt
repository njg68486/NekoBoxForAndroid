package moe.matsuri.nb4a.proxy.xhttp

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class XhttpSettingsActivity : ProfileSettingsActivity<XhttpBean>() {
    override fun createEntity() = XhttpBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val sess = pbm.add(PreferenceBinding(Type.Text, "sess"))
    private val auth = pbm.add(PreferenceBinding(Type.Text, "auth"))
    private val uuid = pbm.add(PreferenceBinding(Type.Text, "uuid"))
    private val paddingLen = pbm.add(PreferenceBinding(Type.Text, "paddingLen"))
    // 落地信息: 全参数展示 (字段为服务端下发, 仅显示)
    private val landingServer = pbm.add(PreferenceBinding(Type.Text, "landingServer"))
    private val landingPort = pbm.add(PreferenceBinding(Type.TextToInt, "landingPort"))

    override fun XhttpBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun XhttpBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResource(R.xml.xhttp_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }

        // xhttp 全参数明文直显 (不隐藏); 弹窗编辑走明文等宽 modifier
        listOf("password", "sess", "auth", "uuid").forEach { key ->
            findPreference<EditTextPreference>(key)?.apply {
                setOnBindEditTextListener(EditTextPreferenceModifiers.PasswordPlain)
            }
        }
    }
}
