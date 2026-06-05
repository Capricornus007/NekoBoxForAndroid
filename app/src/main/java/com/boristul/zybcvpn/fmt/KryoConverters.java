package com.boristul.zybcvpn.fmt;

import androidx.room.TypeConverter;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import com.boristul.zybcvpn.database.SubscriptionBean;
import com.boristul.zybcvpn.fmt.http.HttpBean;
import com.boristul.zybcvpn.fmt.hysteria.HysteriaBean;
import com.boristul.zybcvpn.fmt.internal.ChainBean;
import com.boristul.zybcvpn.fmt.mieru.MieruBean;
import com.boristul.zybcvpn.fmt.naive.NaiveBean;
import com.boristul.zybcvpn.fmt.shadowsocks.ShadowsocksBean;
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean;
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean;
import com.boristul.zybcvpn.fmt.socks.SOCKSBean;
import com.boristul.zybcvpn.fmt.ssh.SSHBean;
import com.boristul.zybcvpn.fmt.trojan.TrojanBean;
import com.boristul.zybcvpn.fmt.trojan_go.TrojanGoBean;
import com.boristul.zybcvpn.fmt.tuic.TuicBean;
import com.boristul.zybcvpn.fmt.v2ray.VMessBean;
import com.boristul.zybcvpn.fmt.wireguard.WireGuardBean;
import com.boristul.zybcvpn.ktx.KryosKt;
import com.boristul.zybcvpn.ktx.Logs;
import moe.matsuri.nb4a.proxy.config.ConfigBean;
import moe.matsuri.nb4a.proxy.neko.NekoBean;
import moe.matsuri.nb4a.utils.JavaUtil;

public class KryoConverters {

    private static final byte[] NULL = new byte[0];

    @TypeConverter
    public static byte[] serialize(Serializable bean) {
        if (bean == null) return NULL;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBufferOutput buffer = KryosKt.byteBuffer(out);
        bean.serializeToBuffer(buffer);
        buffer.flush();
        buffer.close();
        return out.toByteArray();
    }

    public static <T extends Serializable> T deserialize(T bean, byte[] bytes) {
        if (bytes == null) return bean;
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        ByteBufferInput buffer = KryosKt.byteBuffer(input);
        try {
            bean.deserializeFromBuffer(buffer);
        } catch (KryoException e) {
            Logs.INSTANCE.w(e);
        }
        bean.initializeDefaultValues();
        return bean;
    }

    @TypeConverter
    public static SOCKSBean socksDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new SOCKSBean(), bytes);
    }

    @TypeConverter
    public static HttpBean httpDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new HttpBean(), bytes);
    }

    @TypeConverter
    public static ShadowsocksBean shadowsocksDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new ShadowsocksBean(), bytes);
    }

    @TypeConverter
    public static ConfigBean configDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new ConfigBean(), bytes);
    }

    @TypeConverter
    public static VMessBean vmessDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new VMessBean(), bytes);
    }

    @TypeConverter
    public static TrojanBean trojanDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new TrojanBean(), bytes);
    }

    @TypeConverter
    public static TrojanGoBean trojanGoDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new TrojanGoBean(), bytes);
    }

    @TypeConverter
    public static MieruBean mieruDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new MieruBean(), bytes);
    }

    @TypeConverter
    public static NaiveBean naiveDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new NaiveBean(), bytes);
    }

    @TypeConverter
    public static HysteriaBean hysteriaDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new HysteriaBean(), bytes);
    }

    @TypeConverter
    public static SSHBean sshDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new SSHBean(), bytes);
    }

    @TypeConverter
    public static WireGuardBean wireguardDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new WireGuardBean(), bytes);
    }

    @TypeConverter
    public static TuicBean tuicDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new TuicBean(), bytes);
    }

    @TypeConverter
    public static ShadowTLSBean shadowTLSDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new ShadowTLSBean(), bytes);
    }

    @TypeConverter
    public static AnyTLSBean anyTLSDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new AnyTLSBean(), bytes);
    }


    @TypeConverter
    public static ChainBean chainDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new ChainBean(), bytes);
    }

    @TypeConverter
    public static NekoBean nekoDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new NekoBean(), bytes);
    }

    @TypeConverter
    public static SubscriptionBean subscriptionDeserialize(byte[] bytes) {
        if (JavaUtil.isEmpty(bytes)) return null;
        return deserialize(new SubscriptionBean(), bytes);
    }

}
