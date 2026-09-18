package io.nekohasekai.sagernet.fmt.internal;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.fmt.KryoConverters;
import io.nekohasekai.sagernet.fmt.Serializable;

public class BalancerBean extends InternalBean {

    public static final int TYPE_LIST = 0;
    public static final int TYPE_GROUP = 1;

    // Kept at the value this fork always hardcoded so existing balancers behave the same.
    public static final int DEFAULT_TOLERANCE = 50;
    // The core stores urltest tolerance as a uint16 number of milliseconds.
    public static final int MAX_TOLERANCE = 65535;

    public Integer type;
    public String strategy;
    public List<Long> proxies;
    public Long groupId;

    public String probeUrl;
    public Integer probeInterval;
    // urltest switching tolerance in milliseconds: a member is only taken over
    // when it is faster than the current one by more than this much.
    // (OwnBox 934eb6fd2 made the previously hardcoded 50 configurable.)
    public Integer probeTolerance;
    public String nameFilter;
    public String nameFilter1;
    public Boolean useLandingProxy;
    public Boolean useFrontProxy;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (name == null) name = "";
        if (strategy == null) strategy = "";
        if (type == null) type = TYPE_LIST;
        if (proxies == null) proxies = new ArrayList<>();
        if (groupId == null) groupId = 0L;
        if (probeUrl == null) probeUrl = "";
        if (probeInterval == null) probeInterval = 300;
        if (probeTolerance == null) probeTolerance = DEFAULT_TOLERANCE;
        if (nameFilter == null) nameFilter = "";
        if (nameFilter1 == null) nameFilter1 = "";
        if (useLandingProxy == null) useLandingProxy = false;
        if (useFrontProxy == null) useFrontProxy = false;
    }

    @Override
    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        } else {
            return "Balancer " + Math.abs(hashCode());
        }
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(6);
        output.writeInt(type);
        output.writeString(strategy);
        switch (type) {
            case TYPE_LIST: {
                int length = proxies.size();
                output.writeInt(length);
                for (Long proxy : proxies) {
                    output.writeLong(proxy);
                }
                break;
            }
            case TYPE_GROUP: {
                output.writeLong(groupId);
                break;
            }
        }
        output.writeString(probeUrl);
        output.writeInt(probeInterval);
        if (type == TYPE_GROUP) {
            output.writeString(nameFilter);
            output.writeString(nameFilter1);
            output.writeBoolean(useLandingProxy);
            output.writeBoolean(useFrontProxy);
        }
        output.writeInt(toleranceMs());
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        type = input.readInt();
        strategy = input.readString();
        switch (type) {
            case TYPE_LIST: {
                int length = input.readInt();
                proxies = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    proxies.add(input.readLong());
                }
                break;
            }
            case TYPE_GROUP: {
                groupId = input.readLong();
                break;
            }
        }
        if (version >= 1) {
            probeUrl = input.readString();
            probeInterval = input.readInt();
        }
        if (version >= 2 && type == TYPE_GROUP) {
            nameFilter = input.readString();
        }
        if (version >= 3 && type == TYPE_GROUP) {
            nameFilter1 = input.readString();
        }
        if (version >= 4 && type == TYPE_GROUP) {
            useLandingProxy = input.readBoolean();
        }
        if (version >= 5 && type == TYPE_GROUP) {
            useFrontProxy = input.readBoolean();
        }
        if (version >= 6) {
            probeTolerance = input.readInt();
        }
    }

    /**
     * urltest tolerance in milliseconds, clamped into the range the core can store.
     */
    public int toleranceMs() {
        int value = probeTolerance == null ? DEFAULT_TOLERANCE : probeTolerance;
        if (value < 0) {
            value = 0;
        } else if (value > MAX_TOLERANCE) {
            value = MAX_TOLERANCE;
        }
        return value;
    }

    @NonNull
    @Override
    public BalancerBean clone() {
        return KryoConverters.deserialize(new BalancerBean(), KryoConverters.serialize(this));
    }

    public static final Serializable.CREATOR<BalancerBean> CREATOR = new Serializable.CREATOR<BalancerBean>() {
        @NonNull
        @Override
        public BalancerBean newInstance() {
            return new BalancerBean();
        }

        @Override
        public BalancerBean[] newArray(int size) {
            return new BalancerBean[size];
        }
    };

    public boolean isInsecure() {
        return false;
    }

}
