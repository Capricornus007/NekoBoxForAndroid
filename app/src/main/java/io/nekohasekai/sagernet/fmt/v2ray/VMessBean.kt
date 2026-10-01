package io.nekohasekai.sagernet.fmt.v2ray

import android.os.Parcelable
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable

class VMessBean : StandardV2RayBean() {
    @JvmField
    var alterId: Int? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        alterId = (if (alterId != null) alterId else 0)
        if (alterId!! == -1) {
            encryption = (if (!encryption.isNullOrBlank()) encryption else "")
        } else {
            if (!(isVLESS)) {
                encryption = (if (!encryption.isNullOrBlank()) encryption else "auto")
            }
        }
    }

    override fun clone(): VMessBean = KryoConverters.deserialize(VMessBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<VMessBean> = object : Serializable.CREATOR<VMessBean>() {
            override fun newInstance() = VMessBean()
            override fun newArray(size: Int): Array<VMessBean?> = arrayOfNulls(size)
        }
    }
}
