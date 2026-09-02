package moe.matsuri.nb4a.proxy.xhttp

import io.nekohasekai.sagernet.ktx.urlSafe
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * 黑石 xhttp 节点 -> sing-box outbound JSON (type=xhttp, 由 libcore/protocol/xhttp 注册).
 *
 * 拨号目标是鉴权网关 (serverAddress/serverPort 已在解析时填 gateway);
 * tls/servername/xhttp-opts 等官方配置字段全是伪装, 直接忽略.
 */
fun buildSingBoxOutboundXhttpBean(bean: XhttpBean): SingBoxOption {
    val json = JSONObject().apply {
        put("type", "xhttp")
        put("server", bean.serverAddress)
        put("server_port", bean.serverPort)
        if (bean.password.isNotBlank()) put("password", bean.password)
        if (bean.sess.isNotBlank()) put("sess", bean.sess)
        if (bean.auth.isNotBlank()) put("auth", bean.auth)
        if (bean.uuid.isNotBlank()) put("uuid", bean.uuid)
    }
    return CustomSingBoxOption(json.toString(2))
}

fun XhttpBean.toUri(): String {
    val sb = StringBuilder("heysocks://")
        .append(serverAddress.wrapIPV6Host())
        .append(":").append(serverPort)
        .append("?sess=").append(sess)
        .append("&auth=").append(auth)
        .append("&fake_net_tcp=").append(sess)
        .append("&padding_len=").append(paddingLen)
    if (password.isNotBlank()) sb.append("&password=").append(password)
    if (!name.isNullOrBlank()) sb.append("#").append(name.urlSafe())
    return sb.toString()
}

fun parseXhttpLink(url: String): XhttpBean {
    // heysocks://gateway:port?sess=..&auth=..&password=..&fake_net_tcp=..&padding_len=..#name
    val link = url.replace("heysocks://", "https://a:a@").toHttpUrlOrNull()
        ?: error("Invalid heysocks URL: $url")

    return XhttpBean().apply {
        serverAddress = link.host
        serverPort = if (link.port > 0) link.port else 443
        name = link.fragment ?: "xhttp $serverAddress:$serverPort"
        password = link.queryParameter("password") ?: ""
        sess = link.queryParameter("sess")
            ?: link.queryParameter("fake_net_tcp") ?: ""
        auth = link.queryParameter("auth")
            ?: link.queryParameter("token") ?: ""
        paddingLen = link.queryParameter("padding_len") ?: "8-64"
    }
}
