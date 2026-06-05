package com.boristul.zybcvpn.aidl;

import com.boristul.zybcvpn.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);

  int urlTest();
}
