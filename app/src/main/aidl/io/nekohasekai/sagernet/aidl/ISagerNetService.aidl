package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);
  oneway void resetTraffic(in long[] profileIds);

  oneway void observeTailscale(in ISagerNetServiceCallback cb, long sessionId, long profileId, String expectedIdentity);
  oneway void startTailscaleCheck(in ISagerNetServiceCallback cb, long sessionId, long profileId, String expectedIdentity);
  oneway void closeTailscaleSession(in ISagerNetServiceCallback cb, long sessionId);
  oneway void pingTailscalePeer(in ISagerNetServiceCallback cb, long sessionId, long requestId, String peerId, int timeoutMs);
  oneway void setTailscaleExitNode(in ISagerNetServiceCallback cb, long sessionId, long requestId, String peerId, String expectedSavedSelection);
  oneway void cancelTailscaleRequest(in ISagerNetServiceCallback cb, long sessionId, long requestId);

  int urlTest();
  // Connection test through the running outbound of one profile in the current
  // configuration (a Tailscale node used as a hop counts); fails when it is not part of it.
  int urlTestProfile(long profileId);
  // Peers of a running Tailscale profile as JSON (name, dnsName, ips, online, exitNode).
  String tailscalePeers(long profileId);
  // Ids of the Tailscale profiles whose nodes this service runs, as a JSON array. A node
  // has one saved identity, so nobody else may start one of these while the service runs.
  String runningTailscaleProfiles();

  // Live routed connections as JSON (ConnectionEntry array); closed ones too when the
  // bounded history is enabled through connection diagnostics.
  String connections(boolean includeClosed);
}
