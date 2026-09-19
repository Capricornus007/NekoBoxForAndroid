package io.nekohasekai.sagernet.fmt;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import io.nekohasekai.sagernet.ktx.NetsKt;
import moe.matsuri.nb4a.utils.JavaUtil;

public abstract class AbstractBean extends Serializable {

    public String serverAddress;
    public Integer serverPort;

    public String name;

    //

    public String customOutboundJson;
    public String customConfigJson;

    //
    public transient String finalAddress;
    public transient int finalPort;

    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            return displayAddress();
        }
    }

    public String displayAddress() {
        return NetsKt.wrapIPV6Host(serverAddress) + ":" + serverPort;
    }

    public String network() {
        return "tcp,udp";
    }

    public boolean canMapping() {
        return true;
    }

    @Override
    public void initializeDefaultValues() {
        if (JavaUtil.isNullOrBlank(serverAddress)) {
            serverAddress = "127.0.0.1";
        } else if (serverAddress.startsWith("[") && serverAddress.endsWith("]")) {
            serverAddress = NetsKt.unwrapIPV6Host(serverAddress);
        }
        if (serverPort == null) {
            serverPort = 1080;
        }
        if (name == null) name = "";

        finalAddress = serverAddress;
        finalPort = serverPort;

        if (customOutboundJson == null) customOutboundJson = "";
        if (customConfigJson == null) customConfigJson = "";
    }


    private transient boolean serializeWithoutName;

    @Override
    public void serializeToBuffer(@NonNull ByteBufferOutput output) {
        serialize(output);

        output.writeInt(1);
        if (!serializeWithoutName) {
            output.writeString(name);
        }
        output.writeString(customOutboundJson);
        output.writeString(customConfigJson);
    }

    @Override
    public void deserializeFromBuffer(@NonNull ByteBufferInput input) {
        deserialize(input);

        int extraVersion = input.readInt();

        name = input.readString();
        customOutboundJson = input.readString();
        customConfigJson = input.readString();
    }

    public void serialize(ByteBufferOutput output) {
        output.writeString(serverAddress);
        output.writeInt(serverPort);
    }

    public void deserialize(ByteBufferInput input) {
        serverAddress = input.readString();
        serverPort = input.readInt();
    }

    // Some sing-box options are a nullable bool (*bool) whose core default is "on"
    // (e.g. DialerOptions.udp_fragment on the Hysteria2 / TUIC outbounds). Storing them
    // as a plain Boolean would lose the "not written at all" state, so the profile stream
    // uses 0 = unset, 1 = on, 2 = off instead of a single boolean.
    protected static int triStateToInt(Boolean value) {
        if (value == null) return 0;
        return value ? 1 : 2;
    }

    protected static Boolean readTriState(int mode) {
        switch (mode) {
            case 1:
                return true;
            case 2:
                return false;
            default:
                return null;
        }
    }

    @NotNull
    @Override
    public abstract AbstractBean clone();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        try {
            serializeWithoutName = true;
            ((AbstractBean) o).serializeWithoutName = true;
            return Arrays.equals(KryoConverters.serialize(this), KryoConverters.serialize((AbstractBean) o));
        } finally {
            serializeWithoutName = false;
            ((AbstractBean) o).serializeWithoutName = false;
        }
    }

    @Override
    public int hashCode() {
        try {
            serializeWithoutName = true;
            return Arrays.hashCode(KryoConverters.serialize(this));
        } finally {
            serializeWithoutName = false;
        }
    }

    @NotNull
    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + JavaUtil.gson.toJson(this);
    }
}
