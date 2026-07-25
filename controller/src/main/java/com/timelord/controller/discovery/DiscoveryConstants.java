package com.timelord.controller.discovery;

final class DiscoveryConstants {
    static final String PROTOCOL_NAME = "timelord-discovery";
    static final int PROTOCOL_VERSION = 1;

    static final String TYPE_DISCOVER_CONTROLLER = "DISCOVER_CONTROLLER";
    static final String TYPE_CONTROLLER_AVAILABLE = "CONTROLLER_AVAILABLE";
    static final String TYPE_CONTROLLER_ANNOUNCEMENT = "CONTROLLER_ANNOUNCEMENT";

    static final int MAX_PACKET_SIZE = 4096;

    private DiscoveryConstants() {
    }
}
