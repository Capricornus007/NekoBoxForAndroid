package io.nekohasekai.sagernet.fmt.openvpn;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

/**
 * OpenVPN 剖面。對應核心的 `openvpn` **endpoint**（不是 outbound：sing-box 1.15 把 OpenVPN
 * 跟 WireGuard 一樣做成 endpoint，見 `include/openvpn.go` 只註冊 endpoint 與 DNS transport）。
 *
 * 欄位命名刻意跟核心 `option/openvpn.go` 的 `OpenVPNClientEndpointOptions` 對齊，這樣
 * `ConfigBuilder` 裡就是逐欄位搬過去、不需要中間名轉換，看一個欄位就能回到源頭查它收哪些值。
 *
 * 憑證／金鑰（ca、client cert、client key、tls-auth/crypt 的 key）是 `.ovpn` 解出來的多行 PEM，
 * 這裡一律存「整段原文」，空字串代表沒帶；由 `OpenVPNConfigFmt` 負責從檔案搬進來。
 */
public class OpenVPNBean extends AbstractBean {

    // tls | static_key（核心 enum）
    public String mode;
    // udp / udp4 / udp6 / tcp / tcp4 / tcp6（核心 enum；空＝核心預設 udp）
    // 欄位名刻意叫 transport：AbstractBean 已經有個 network() 方法，同名叫 network 會在 Kotlin
    // 那側產生「屬性還是方法」的歧義。
    public String transport;
    public Boolean remoteRandom;

    // 伺服器清單：每列 "host:port[:proto]"，proto 可留空。
    public String servers;

    public String username;
    public String password;
    public String authRetry;
    public String staticChallenge;
    public Boolean staticChallengeEcho;

    public String ca;
    public String clientCert;
    public String clientKey;

    // control wrap：type = tls_auth | tls_crypt | tls_crypt_v2；direction 只有 tls_auth 能用
    // （核心 protocol/openvpn/client.go:229/234 會拒絕其他組合）。
    public String controlWrapType;
    public String controlWrapKey;
    public String controlWrapDirection;

    // static_key 模式專用（核心明確拒絕在 tls 模式使用）。
    public String staticKey;
    public String keyDirection;

    public String cipher;
    public String dataCiphers;
    public String dataCiphersFallback;
    public String auth;
    public String compression;
    public String compressionLzo;
    public String allowCompression;

    public Integer replayWindow;
    public String replayWindowTime;

    public Integer mssFix;
    public Integer fragment;
    public Integer mtu;
    public String topology;

    public String pingInterval;
    public String pingRestart;
    public String handshakeWindow;
    public String tlsTimeout;
    public String renegotiateInterval;
    public Boolean renegotiateDisabled;
    public Integer explicitExitNotify;

    public Boolean redirectGateway;
    public String redirectGatewayFlags;
    public Boolean redirectPrivate;
    public Boolean blockIPv6;
    public Boolean routeNoPull;
    public String routes;
    public String pullFilters;

    public String tlsServerName;
    public String tlsServerNameType;
    public String remoteCertificateTls;
    public String nsCertificateType;
    public String peerFingerprint;
    public String remoteCertificateKu;
    public String remoteCertificateEku;
    public String tlsGroups;
    public String tlsCipher;
    public String tlsVersionMin;
    public String tlsVersionMax;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (mode == null) mode = "tls";
        if (transport == null) transport = "";
        if (remoteRandom == null) remoteRandom = false;
        if (servers == null) servers = "";
        if (username == null) username = "";
        if (password == null) password = "";
        if (authRetry == null) authRetry = "";
        if (staticChallenge == null) staticChallenge = "";
        if (staticChallengeEcho == null) staticChallengeEcho = false;
        if (ca == null) ca = "";
        if (clientCert == null) clientCert = "";
        if (clientKey == null) clientKey = "";
        if (controlWrapType == null) controlWrapType = "";
        if (controlWrapKey == null) controlWrapKey = "";
        if (controlWrapDirection == null) controlWrapDirection = "";
        if (staticKey == null) staticKey = "";
        if (keyDirection == null) keyDirection = "";
        if (cipher == null) cipher = "";
        if (dataCiphers == null) dataCiphers = "";
        if (dataCiphersFallback == null) dataCiphersFallback = "";
        if (auth == null) auth = "";
        if (compression == null) compression = "";
        if (compressionLzo == null) compressionLzo = "";
        if (allowCompression == null) allowCompression = "";
        if (replayWindow == null) replayWindow = 0;
        if (replayWindowTime == null) replayWindowTime = "";
        if (mssFix == null) mssFix = 0;
        if (fragment == null) fragment = 0;
        if (mtu == null) mtu = 0;
        if (topology == null) topology = "";
        if (pingInterval == null) pingInterval = "";
        if (pingRestart == null) pingRestart = "";
        if (handshakeWindow == null) handshakeWindow = "";
        if (tlsTimeout == null) tlsTimeout = "";
        if (renegotiateInterval == null) renegotiateInterval = "";
        if (renegotiateDisabled == null) renegotiateDisabled = false;
        if (explicitExitNotify == null) explicitExitNotify = 0;
        if (redirectGateway == null) redirectGateway = false;
        if (redirectGatewayFlags == null) redirectGatewayFlags = "";
        if (redirectPrivate == null) redirectPrivate = false;
        if (blockIPv6 == null) blockIPv6 = false;
        if (routeNoPull == null) routeNoPull = false;
        if (routes == null) routes = "";
        if (pullFilters == null) pullFilters = "";
        if (tlsServerName == null) tlsServerName = "";
        if (tlsServerNameType == null) tlsServerNameType = "";
        if (remoteCertificateTls == null) remoteCertificateTls = "";
        if (nsCertificateType == null) nsCertificateType = "";
        if (peerFingerprint == null) peerFingerprint = "";
        if (remoteCertificateKu == null) remoteCertificateKu = "";
        if (remoteCertificateEku == null) remoteCertificateEku = "";
        if (tlsGroups == null) tlsGroups = "";
        if (tlsCipher == null) tlsCipher = "";
        if (tlsVersionMin == null) tlsVersionMin = "";
        if (tlsVersionMax == null) tlsVersionMax = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(mode);
        output.writeString(transport);
        output.writeBoolean(remoteRandom);
        output.writeString(servers);
        output.writeString(username);
        output.writeString(password);
        output.writeString(authRetry);
        output.writeString(staticChallenge);
        output.writeBoolean(staticChallengeEcho);
        output.writeString(ca);
        output.writeString(clientCert);
        output.writeString(clientKey);
        output.writeString(controlWrapType);
        output.writeString(controlWrapKey);
        output.writeString(controlWrapDirection);
        output.writeString(staticKey);
        output.writeString(keyDirection);
        output.writeString(cipher);
        output.writeString(dataCiphers);
        output.writeString(dataCiphersFallback);
        output.writeString(auth);
        output.writeString(compression);
        output.writeString(compressionLzo);
        output.writeString(allowCompression);
        output.writeInt(replayWindow);
        output.writeString(replayWindowTime);
        output.writeInt(mssFix);
        output.writeInt(fragment);
        output.writeInt(mtu);
        output.writeString(topology);
        output.writeString(pingInterval);
        output.writeString(pingRestart);
        output.writeString(handshakeWindow);
        output.writeString(tlsTimeout);
        output.writeString(renegotiateInterval);
        output.writeBoolean(renegotiateDisabled);
        output.writeInt(explicitExitNotify);
        output.writeBoolean(redirectGateway);
        output.writeString(redirectGatewayFlags);
        output.writeBoolean(redirectPrivate);
        output.writeBoolean(blockIPv6);
        output.writeBoolean(routeNoPull);
        output.writeString(routes);
        output.writeString(pullFilters);
        output.writeString(tlsServerName);
        output.writeString(tlsServerNameType);
        output.writeString(remoteCertificateTls);
        output.writeString(nsCertificateType);
        output.writeString(peerFingerprint);
        output.writeString(remoteCertificateKu);
        output.writeString(remoteCertificateEku);
        output.writeString(tlsGroups);
        output.writeString(tlsCipher);
        output.writeString(tlsVersionMin);
        output.writeString(tlsVersionMax);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        mode = input.readString();
        transport = input.readString();
        remoteRandom = input.readBoolean();
        servers = input.readString();
        username = input.readString();
        password = input.readString();
        authRetry = input.readString();
        staticChallenge = input.readString();
        staticChallengeEcho = input.readBoolean();
        ca = input.readString();
        clientCert = input.readString();
        clientKey = input.readString();
        controlWrapType = input.readString();
        controlWrapKey = input.readString();
        controlWrapDirection = input.readString();
        staticKey = input.readString();
        keyDirection = input.readString();
        cipher = input.readString();
        dataCiphers = input.readString();
        dataCiphersFallback = input.readString();
        auth = input.readString();
        compression = input.readString();
        compressionLzo = input.readString();
        allowCompression = input.readString();
        replayWindow = input.readInt();
        replayWindowTime = input.readString();
        mssFix = input.readInt();
        fragment = input.readInt();
        mtu = input.readInt();
        topology = input.readString();
        pingInterval = input.readString();
        pingRestart = input.readString();
        handshakeWindow = input.readString();
        tlsTimeout = input.readString();
        renegotiateInterval = input.readString();
        renegotiateDisabled = input.readBoolean();
        explicitExitNotify = input.readInt();
        redirectGateway = input.readBoolean();
        redirectGatewayFlags = input.readString();
        redirectPrivate = input.readBoolean();
        blockIPv6 = input.readBoolean();
        routeNoPull = input.readBoolean();
        routes = input.readString();
        pullFilters = input.readString();
        tlsServerName = input.readString();
        tlsServerNameType = input.readString();
        remoteCertificateTls = input.readString();
        nsCertificateType = input.readString();
        peerFingerprint = input.readString();
        remoteCertificateKu = input.readString();
        remoteCertificateEku = input.readString();
        tlsGroups = input.readString();
        tlsCipher = input.readString();
        tlsVersionMin = input.readString();
        tlsVersionMax = input.readString();
    }

    @NotNull
    @Override
    public String toString() {
        return "OPENVPN";
    }

    @NotNull
    @Override
    public OpenVPNBean clone() {
        return KryoConverters.deserialize(new OpenVPNBean(), KryoConverters.serialize(this));
    }

    public static final Creator<OpenVPNBean> CREATOR = new CREATOR<OpenVPNBean>() {
        @NonNull
        @Override
        public OpenVPNBean newInstance() {
            return new OpenVPNBean();
        }

        @Override
        public OpenVPNBean[] newArray(int size) {
            return new OpenVPNBean[size];
        }
    };
}
