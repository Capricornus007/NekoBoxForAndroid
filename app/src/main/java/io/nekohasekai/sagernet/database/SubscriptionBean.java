package io.nekohasekai.sagernet.database;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import io.nekohasekai.sagernet.fmt.Serializable;

public class SubscriptionBean extends Serializable {

    public Integer type;
    public String link;
    public Boolean forceResolve;
    public Boolean deduplication;
    public Boolean updateWhenConnectedOnly;
    public String customUserAgent;
    // isai：訂閱請求附硬體識別/自訂 header（序列化 v6 起）。
    public Boolean sendHwid;
    public String customHwidParams;
    public String customHeaders;
    public Boolean autoUpdate;
    public Integer autoUpdateDelay;
    public Integer lastUpdated;
    public Integer filterMode;
    public String filterRegex;
    // 只解析「這組訂閱自己的伺服器域名」；空＝未設定，沿用全域 DNS。
    // ⚠️ 10-01：這個欄位之前**完全沒進序列化**（版本停在 3），所以使用者在 UI 填了、
    //    當次生成的配置有效，但重啟 App／重載群組後就變 null、dns-sub 規則消失。
    public String serverDnsResolver;

    // https://github.com/crossutility/Quantumult/blob/master/extra-subscription-feature.md

    public String subscriptionUserinfo;

    public SubscriptionBean() {
    }

    @Override
    public void serializeToBuffer(ByteBufferOutput output) {
        output.writeInt(6);

        output.writeInt(type);

        output.writeString(link);

        output.writeBoolean(forceResolve);
        output.writeBoolean(deduplication);
        output.writeBoolean(updateWhenConnectedOnly);
        output.writeString(customUserAgent);
        output.writeBoolean(autoUpdate);
        output.writeInt(autoUpdateDelay);
        output.writeInt(lastUpdated);

        output.writeString(subscriptionUserinfo);

        // v2
        output.writeInt(filterMode);
        output.writeString(filterRegex);

        // v5（原 v4）：serverDnsResolver 之前漏了持久化，補上（見欄位註解）。
        output.writeString(serverDnsResolver);
        // v6（isai）：訂閱 HWID 與自訂 header。
        output.writeBoolean(sendHwid);
        output.writeString(customHwidParams);
        output.writeString(customHeaders);
    }

    public void serializeForShare(ByteBufferOutput output) {
        // v3（isai）：尾端追加 sendHwid/customHwidParams/serverDnsResolver/customHeaders
        // （順序對齊 isai 上游 share v2，合併前 v3 尚未發佈，直接補齊 serverDnsResolver）。
        output.writeInt(3);

        output.writeInt(type);

        output.writeString(link);

        output.writeBoolean(forceResolve);
        output.writeBoolean(deduplication);
        output.writeBoolean(updateWhenConnectedOnly);
        output.writeString(customUserAgent);
        output.writeBoolean(sendHwid);
        output.writeString(customHwidParams);
        output.writeString(serverDnsResolver);
        output.writeString(customHeaders);
    }

    @Override
    public void deserializeFromBuffer(ByteBufferInput input) {
        int version = input.readInt();

        type = input.readInt();
        link = input.readString();
        forceResolve = input.readBoolean();
        deduplication = input.readBoolean();
        updateWhenConnectedOnly = input.readBoolean();
        customUserAgent = input.readString();
        autoUpdate = input.readBoolean();
        autoUpdateDelay = input.readInt();
        lastUpdated = input.readInt();
        subscriptionUserinfo = input.readString();

        // v2
        if (version >= 2) {
            filterMode = input.readInt();
            filterRegex = input.readString();
        }
        // v3、v4 的舊資料在 filterRegex 之後多寫了一對欄位（布林＋字串，該功能已整個移除）。
        // 功能代碼不存在，但讀舊列時仍要把這兩格吃掉，否則後面所有欄位會錯位、舊訂閱直接讀壞。
        if (version == 3 || version == 4) {
            input.readBoolean();
            input.readString();
        }
        // v4 起才有 serverDnsResolver；更舊的資料保持 null，由 initializeDefaultValues 補空字串。
        if (version >= 4) {
            serverDnsResolver = input.readString();
        }
        // v6（isai）起才有 HWID/自訂 header。
        if (version >= 6) {
            sendHwid = input.readBoolean();
            customHwidParams = input.readString();
            customHeaders = input.readString();
        }
    }

    public void deserializeFromShare(ByteBufferInput input) {
        int version = input.readInt();

        type = input.readInt();
        link = input.readString();
        forceResolve = input.readBoolean();
        deduplication = input.readBoolean();
        updateWhenConnectedOnly = input.readBoolean();
        customUserAgent = input.readString();
        // 舊版分享格式（v1）尾端帶一對已移除功能的欄位，照吃掉以對齊結尾。
        if (version == 1) {
            input.readBoolean();
            input.readString();
        }
        // v3（isai）：分享尾端追加 HWID/自訂 HWID 參數/訂閱 DNS resolver/自訂 header。
        if (version >= 3) {
            sendHwid = input.readBoolean();
            customHwidParams = input.readString();
            serverDnsResolver = input.readString();
            customHeaders = input.readString();
        }
    }

    @Override
    public void initializeDefaultValues() {
        if (type == null) type = 0;
        if (link == null) link = "";
        if (forceResolve == null) forceResolve = false;
        if (deduplication == null) deduplication = false;
        if (updateWhenConnectedOnly == null) updateWhenConnectedOnly = false;
        if (customUserAgent == null) customUserAgent = "";
        if (sendHwid == null) sendHwid = false;
        if (customHwidParams == null) customHwidParams = "";
        if (customHeaders == null) customHeaders = "";
        if (autoUpdate == null) autoUpdate = false;
        if (autoUpdateDelay == null) autoUpdateDelay = 1440;
        if (lastUpdated == null) lastUpdated = 0;
        if (filterMode == null) filterMode = 0;
        if (filterRegex == null) filterRegex = "";
        if (serverDnsResolver == null) serverDnsResolver = "";
    }

    public static final Creator<SubscriptionBean> CREATOR = new CREATOR<SubscriptionBean>() {
        @NonNull
        @Override
        public SubscriptionBean newInstance() {
            return new SubscriptionBean();
        }

        @Override
        public SubscriptionBean[] newArray(int size) {
            return new SubscriptionBean[size];
        }
    };

}
