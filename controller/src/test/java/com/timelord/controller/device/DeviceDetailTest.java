package com.timelord.controller.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceDetailTest {

    @Test
    void dropsLinkLocalAndLoopbackAddressesInEitherFormat() {
        Device device = new Device(UUID.randomUUID());
        device.setLocalIpAddresses(List.of(
                "fe80::53c8:b8e8:e5f2:86b3",
                "169.254.96.242",
                "192.168.1.230",
                "Ethernet: 10.0.0.5",
                "Wi-Fi: fe80::1",
                "127.0.0.1"));

        DeviceDetail detail = DeviceDetail.from(device);

        assertThat(detail.localIpAddresses()).containsExactly("192.168.1.230", "Ethernet: 10.0.0.5");
    }

    @Test
    void keepsUnparseableEntriesRatherThanSilentlyDroppingThem() {
        Device device = new Device(UUID.randomUUID());
        device.setLocalIpAddresses(List.of("not-an-ip-address"));

        DeviceDetail detail = DeviceDetail.from(device);

        assertThat(detail.localIpAddresses()).containsExactly("not-an-ip-address");
    }

    @Test
    void handlesNullLocalIpAddresses() {
        Device device = new Device(UUID.randomUUID());
        device.setLocalIpAddresses(null);

        DeviceDetail detail = DeviceDetail.from(device);

        assertThat(detail.localIpAddresses()).isEmpty();
    }
}
