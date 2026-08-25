package io.nekohasekai.sagernet.fmt.shadowquic;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class ShadowQUICBean extends AbstractBean {

    public String username;
    public String password;
    public String sni;
    public String congestionControl;
    public String alpn;
    public Boolean udpOverStream;
    public Boolean zeroRTT;
    public Boolean sunnyQUIC;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (username == null) username = "";
        if (password == null) password = "";
        if (sni == null) sni = "";
        if (congestionControl == null) congestionControl = "";
        if (alpn == null) alpn = "";
        if (udpOverStream == null) udpOverStream = false;
        if (zeroRTT == null) zeroRTT = false;
        if (sunnyQUIC == null) sunnyQUIC = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(username);
        output.writeString(password);
        output.writeString(sni);
        output.writeString(congestionControl);
        output.writeString(alpn);
        output.writeBoolean(udpOverStream);
        output.writeBoolean(zeroRTT);
        output.writeBoolean(sunnyQUIC);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        username = input.readString();
        password = input.readString();
        sni = input.readString();
        congestionControl = input.readString();
        alpn = input.readString();
        udpOverStream = input.readBoolean();
        zeroRTT = input.readBoolean();
        sunnyQUIC = input.readBoolean();
    }

    @NotNull
    @Override
    public ShadowQUICBean clone() {
        return KryoConverters.deserialize(new ShadowQUICBean(), KryoConverters.serialize(this));
    }

    public static final Creator<ShadowQUICBean> CREATOR = new CREATOR<ShadowQUICBean>() {
        @NonNull
        @Override
        public ShadowQUICBean newInstance() {
            return new ShadowQUICBean();
        }

        @Override
        public ShadowQUICBean[] newArray(int size) {
            return new ShadowQUICBean[size];
        }
    };
}