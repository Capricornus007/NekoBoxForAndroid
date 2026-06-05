package com.boristul.zybcvpn.aidl;

import com.boristul.zybcvpn.aidl.SpeedDisplayData;
import com.boristul.zybcvpn.aidl.TrafficData;

oneway interface ISagerNetServiceCallback {
  void stateChanged(int state, String profileName, String msg);
  void missingPlugin(String profileName, String pluginName);
  void cbSpeedUpdate(in SpeedDisplayData stats);
  void cbTrafficUpdate(in TrafficData stats);
  void cbSelectorUpdate(long id);
}
