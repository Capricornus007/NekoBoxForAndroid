package io.nekohasekai.sagernet.fmt.byedpi;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

/**
 * byeDPI 深度包检测规避层。它没有服务器：cliStrategy 是原样交给内嵌 byeDPI 的
 * 命令行参数串（--split / --disorder / --fake / --tlsrec ...），桥接层自己补上
 * --ip/--port/--protect-path，见 libcore/protocol/byedpi/args.go。
 * <p>
 * serverAddress/serverPort 对本协议没有意义，固定成本机占位值，只为了让 AbstractBean
 * 的通用链路（显示、DNS 收集）不引入非 IP 的假域名。
 */
public class ByeDPIBean extends AbstractBean {

    public String cliStrategy;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        serverAddress = "127.0.0.1";
        serverPort = 0;
        if (cliStrategy == null) cliStrategy = "";
    }

    @Override
    public String displayAddress() {
        return "byedpi";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(cliStrategy);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        input.readInt();
        super.deserialize(input);
        cliStrategy = input.readString();
    }

    @NotNull
    @Override
    public ByeDPIBean clone() {
        return KryoConverters.deserialize(new ByeDPIBean(), KryoConverters.serialize(this));
    }

    public static final Creator<ByeDPIBean> CREATOR = new CREATOR<ByeDPIBean>() {
        @NonNull
        @Override
        public ByeDPIBean newInstance() {
            return new ByeDPIBean();
        }

        @Override
        public ByeDPIBean[] newArray(int size) {
            return new ByeDPIBean[size];
        }
    };
}
