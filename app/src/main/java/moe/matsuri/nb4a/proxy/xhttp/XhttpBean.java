package moe.matsuri.nb4a.proxy.xhttp;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

/**
 * 黑石 (Heysocks) 官方 "xhttp" 私有协议节点.
 *
 * YAML 字段 (黑石原生):
 *   server/port       落地地址 (仅展示)
 *   gateway           鉴权网关 host:port (真正拨号目标) -> serverAddress/serverPort
 *   password          key1:key2 (AES key 仅由 key1 派生)
 *   sess              68B hex 会话前缀
 *   auth              hex 运行时令牌 (服务端校验)
 *   uuid              伪装字段
 */
public class XhttpBean extends AbstractBean {

    public String password;       // key1:key2
    public String sess;           // 68-byte hex
    public String auth;           // token hex
    public String uuid;           // decoy
    public String paddingLen;     // "8-64"
    public String landingServer;  // 落地 server (展示用)
    public Integer landingPort;   // 落地 port (展示用)

    public static final Creator<XhttpBean> CREATOR = new CREATOR<XhttpBean>() {
        @NonNull
        @Override
        public XhttpBean newInstance() {
            return new XhttpBean();
        }

        public XhttpBean[] newArray(int size) {
            return new XhttpBean[size];
        }
    };

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (password == null) password = "";
        if (sess == null) sess = "";
        if (auth == null) auth = "";
        if (uuid == null) uuid = "";
        if (paddingLen == null) paddingLen = "8-64";
        if (landingServer == null) landingServer = "";
        if (landingPort == null) landingPort = 0;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(password);
        output.writeString(sess);
        output.writeString(auth);
        output.writeString(uuid);
        output.writeString(paddingLen);
        output.writeString(landingServer);
        output.writeInt(landingPort);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        password = input.readString();
        sess = input.readString();
        auth = input.readString();
        uuid = input.readString();
        if (version >= 1) {
            paddingLen = input.readString();
            landingServer = input.readString();
            landingPort = input.readInt();
        } else {
            paddingLen = "8-64";
            landingServer = "";
            landingPort = 0;
        }
    }

    @NotNull
    @Override
    public XhttpBean clone() {
        return KryoConverters.deserialize(new XhttpBean(), KryoConverters.serialize(this));
    }
}
