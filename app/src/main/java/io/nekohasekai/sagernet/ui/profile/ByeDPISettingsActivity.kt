/******************************************************************************
 * Copyright (C) 2026 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 * (at your option) any later version.                                        *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.byedpi.ByeDPIBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class ByeDPISettingsActivity : ProfileSettingsActivity<ByeDPIBean>() {

    override fun createEntity() = ByeDPIBean().applyDefaultValues()

    override fun ByeDPIBean.init() {
        DataStore.profileName = name
        // byeDPI has no server: address/port are fixed placeholders, only the CLI matters.
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.byedpiCli = cliStrategy
    }

    override fun ByeDPIBean.serialize() {
        name = DataStore.profileName
        serverAddress = "127.0.0.1"
        serverPort = 0
        cliStrategy = DataStore.byedpiCli.trim()
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.byedpi_preferences)
        findPreference<EditTextPreference>(Key.BYEDPI_CLI)!!.setOnBindEditTextListener(
            EditTextPreferenceModifiers.Monospace,
        )
    }
}
