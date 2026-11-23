package com.meshcentral.agent

import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        findPreference<SwitchPreferenceCompat>("pref_remotecontrol")?.apply {
            isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            setOnPreferenceChangeListener { _, value ->
                if (value == true) {
                    AlertDialog.Builder(requireContext()).setTitle(R.string.remote_control)
                        .setMessage(R.string.remote_control_disclosure)
                        .setPositiveButton(R.string.remote_control_enable) { _, _ -> isChecked = true }
                        .setNegativeButton(android.R.string.cancel, null).show()
                    false
                } else true
            }
        }
        findPreference<Preference>("pref_accessibility")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        findPreference<Preference>("pref_accessibility")?.setSummary(when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N -> R.string.remote_control_not_supported
            RemoteControlService.isConnected() -> R.string.remote_control_connected
            else -> R.string.remote_control_not_connected
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsFragment = this;
        visibleScreen = 5;
    }

    override fun onDestroy() {
        if (settingsFragment === this) settingsFragment = null
        g_mainActivity?.settingsChanged()
        super.onDestroy()
    }

    fun exit() {
        g_mainActivity?.settingsChanged()
        findNavController().navigate(R.id.action_settingsFragment_to_FirstFragment)
    }
}
