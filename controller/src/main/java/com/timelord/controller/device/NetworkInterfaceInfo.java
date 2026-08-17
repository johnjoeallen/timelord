package com.timelord.controller.device;

/**
 * One network adapter as reported by the agent, regardless of whether it
 * currently has a routable address — unlike {@code Device.localIpAddresses},
 * which is the filtered, at-a-glance summary. Lets the dashboard show every
 * adapter a device has, not just the ones currently carrying traffic.
 */
public record NetworkInterfaceInfo(String name, String macAddress) {
}
