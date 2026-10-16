package io.nekohasekai.sagernet.fmt.internal;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

public class ChainBean extends InternalBean {

    public static final int STRATEGY_CHAIN = 0;
    public static final int STRATEGY_WATERFALL = 1;
    public static final int STRATEGY_FASTEST = 2;

    public static final int CANDIDATE_MODE_MANUAL = 0;
    public static final int CANDIDATE_MODE_REGEX = 1;

    public List<Long> proxies;
    public int strategy;
    public int candidateMode;
    public long sourceGroupId;
    public String nameRegex;
    public boolean ignoreCase = true;
    public int protocolFilterMode;
    public List<String> protocols;
    public boolean healthCheck;
    public boolean failureCooldown;
    public String healthUrl;

    @Override
    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            String prefix;
            switch (strategy) {
                case STRATEGY_WATERFALL:
                    prefix = "Waterfall";
                    break;
                case STRATEGY_FASTEST:
                    prefix = "Fastest";
                    break;
                default:
                    prefix = "Chain";
                    break;
            }
            return prefix + " " + Math.abs(hashCode());
        }
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (name == null) name = "";

        if (proxies == null) {
            proxies = new ArrayList<>();
        }
        if (nameRegex == null) nameRegex = "";
        if (protocols == null) protocols = new ArrayList<>();
        if (healthUrl == null || healthUrl.isEmpty()) healthUrl = "https://www.gstatic.com/generate_204";
        if (candidateMode != CANDIDATE_MODE_REGEX) {
            candidateMode = CANDIDATE_MODE_MANUAL;
        }
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(5);
        output.writeInt(strategy);
        output.writeInt(candidateMode);
        output.writeLong(sourceGroupId);
        output.writeString(nameRegex);
        output.writeBoolean(ignoreCase);
        output.writeInt(proxies.size());
        for (Long proxy : proxies) {
            output.writeLong(proxy);
        }
        output.writeInt(protocolFilterMode);
        output.writeInt(protocols.size());
        for (String protocol : protocols) output.writeString(protocol);
        output.writeBoolean(healthCheck);
        output.writeBoolean(failureCooldown);
        output.writeString(healthUrl);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        if (version < 1) {
            input.readString();
            input.readInt();
        }
        if (version >= 2) {
            strategy = input.readInt();
        } else {
            strategy = STRATEGY_CHAIN;
        }
        if (version >= 3) {
            candidateMode = input.readInt();
            sourceGroupId = input.readLong();
            nameRegex = input.readString();
            ignoreCase = input.readBoolean();
        } else {
            candidateMode = CANDIDATE_MODE_MANUAL;
            sourceGroupId = 0L;
            nameRegex = "";
            ignoreCase = true;
        }
        int length = input.readInt();
        proxies = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            proxies.add(input.readLong());
        }
        protocolFilterMode = version >= 4 ? input.readInt() : 0;
        protocols = new ArrayList<>();
        if (version >= 4) {
            int count = input.readInt();
            for (int i = 0; i < count; i++) protocols.add(input.readString());
        }
        healthCheck = version >= 5 && input.readBoolean();
        failureCooldown = version >= 5 && input.readBoolean();
        healthUrl = version >= 5 ? input.readString() : "https://www.gstatic.com/generate_204";
    }

    @NotNull
    @Override
    public ChainBean clone() {
        return KryoConverters.deserialize(new ChainBean(), KryoConverters.serialize(this));
    }

    public static final Creator<ChainBean> CREATOR = new CREATOR<ChainBean>() {
        @NonNull
        @Override
        public ChainBean newInstance() {
            return new ChainBean();
        }

        @Override
        public ChainBean[] newArray(int size) {
            return new ChainBean[size];
        }
    };
}
