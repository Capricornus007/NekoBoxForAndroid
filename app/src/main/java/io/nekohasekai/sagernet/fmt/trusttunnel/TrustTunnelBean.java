package io.nekohasekai.sagernet.fmt.trusttunnel;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class TrustTunnelBean extends AbstractBean {

    public String username;
    public String password;
    public String sni;
    public String pinnedCertchainSha256;
    public String quicCongestionControl;
    public Boolean quic;
    public Boolean healthCheck;
    public Boolean allowInsecure;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (username == null) username = "";
        if (password == null) password = "";
        if (sni == null) sni = "";
        if (pinnedCertchainSha256 == null) pinnedCertchainSha256 = "";
        if (quicCongestionControl == null) quicCongestionControl = "";
        if (quic == null) quic = false;
        if (healthCheck == null) healthCheck = false;
        if (allowInsecure == null) allowInsecure = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(username);
        output.writeString(password);
        output.writeString(sni);
        output.writeString(pinnedCertchainSha256);
        output.writeString(quicCongestionControl);
        output.writeBoolean(quic);
        output.writeBoolean(healthCheck);
        output.writeBoolean(allowInsecure);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        username = input.readString();
        password = input.readString();
        sni = input.readString();
        pinnedCertchainSha256 = input.readString();
        quicCongestionControl = input.readString();
        quic = input.readBoolean();
        healthCheck = input.readBoolean();
        allowInsecure = input.readBoolean();
    }

    @NotNull
    @Override
    public TrustTunnelBean clone() {
        return KryoConverters.deserialize(new TrustTunnelBean(), KryoConverters.serialize(this));
    }

    public static final Creator<TrustTunnelBean> CREATOR = new CREATOR<TrustTunnelBean>() {
        @NonNull
        @Override
        public TrustTunnelBean newInstance() {
            return new TrustTunnelBean();
        }

        @Override
        public TrustTunnelBean[] newArray(int size) {
            return new TrustTunnelBean[size];
        }
    };
}