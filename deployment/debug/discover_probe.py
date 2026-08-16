#!/usr/bin/env python3
"""Standalone UDP discovery probe for debugging TimeLord's broadcast discovery
(design brief section 4 / agent/src/discovery.rs), without needing a built
agent. Speaks the same wire protocol as the real agent.

Registers itself as hostname "Timelord Debug" so it's easy to spot in
controller-side logs.

Modes:
  sweep (default)  Auto-detects this machine's local IPv4 subnets and sends
                    DISCOVER_CONTROLLER as a *unicast* packet to every host
                    address in each subnet, instead of one packet to the
                    broadcast address. Use this if plain broadcast discovery
                    doesn't work — e.g. the controller runs behind Docker's
                    userland-proxy port publishing, which (confirmed by
                    testing) relays broadcast-addressed requests into the
                    container fine but drops the reply on the way back out,
                    while a unicast request/reply round-trips correctly
                    through the exact same relay. Sweeping every host on
                    the subnet sidesteps that by never using the broadcast
                    address at all.
  broadcast         The original approach: one packet to 255.255.255.255.
                    Kept for comparison/regression testing.
  listen-only       Don't send anything, just bind and log whatever arrives
                    on the port. Useful run directly on the controller host
                    to prove packets reach the machine's socket layer at all,
                    independent of Docker/the JVM.

No third-party dependencies — stdlib only, Python 3.7+, Linux (uses `ip
addr` to enumerate interfaces for sweep mode).

Usage:
    python3 discover_probe.py                                  # sweep, default port 45821
    python3 discover_probe.py --mode broadcast --timeout 8
    python3 discover_probe.py --listen-only
    python3 discover_probe.py --send-fake-event                # sweep, then register +
                                                                   submit one synthetic event
                                                                   to whichever controller
                                                                   responds
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import platform
import re
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

PROTOCOL_NAME = "timelord-discovery"
PROTOCOL_VERSION = 1
TYPE_DISCOVER_CONTROLLER = "DISCOVER_CONTROLLER"
TYPE_CONTROLLER_AVAILABLE = "CONTROLLER_AVAILABLE"
HOSTNAME = "Timelord Debug"

# Interfaces that are almost never the physical LAN and would blow up sweep
# time/noise if included (container bridges, VPN tunnels, etc).
DEFAULT_EXCLUDED_INTERFACE_PREFIXES = ("lo", "docker", "veth", "br-", "virbr", "cni", "tun", "tap")


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}")


def local_ipv4_networks(include_virtual: bool) -> list[tuple[str, ipaddress.IPv4Interface]]:
    """Parses `ip -o -4 addr show` to get (interface_name, IPv4Interface)
    for each configured address. Returns [] (with a warning) if `ip` isn't
    available rather than raising, since this is a debug tool."""
    try:
        output = subprocess.run(["ip", "-o", "-4", "addr", "show"], capture_output=True, text=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError) as e:
        log(f"could not run 'ip addr show' to enumerate interfaces: {e}")
        return []

    results = []
    for line in output.splitlines():
        parts = line.split()
        if len(parts) < 4:
            continue
        iface = parts[1]
        if not include_virtual and iface.startswith(DEFAULT_EXCLUDED_INTERFACE_PREFIXES):
            continue
        m = re.search(r"inet (\d+\.\d+\.\d+\.\d+/\d+)", line)
        if not m:
            continue
        try:
            iface_addr = ipaddress.IPv4Interface(m.group(1))
        except ValueError:
            continue
        if iface_addr.ip.is_loopback:
            continue
        results.append((iface, iface_addr))
    return results


def build_request(hostname: str = HOSTNAME) -> tuple[bytes, str, str]:
    request_id = str(uuid.uuid4())
    device_id = str(uuid.uuid4())
    payload = {
        "protocol": PROTOCOL_NAME,
        "protocolVersion": PROTOCOL_VERSION,
        "messageType": TYPE_DISCOVER_CONTROLLER,
        "requestId": request_id,
        "agentVersion": "debug-probe",
        "deviceId": device_id,
        "hostname": hostname,
    }
    return json.dumps(payload).encode("utf-8"), request_id, device_id


def parse_response(data: bytes, from_addr, expected_request_id: str):
    """Returns (is_valid, description, parsed_dict_or_None)."""
    try:
        msg = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return False, f"non-JSON packet ({len(data)} bytes) from {from_addr}: {data!r}", None

    problems = []
    if msg.get("protocol") != PROTOCOL_NAME:
        problems.append(f"protocol={msg.get('protocol')!r}")
    if msg.get("protocolVersion") != PROTOCOL_VERSION:
        problems.append(f"protocolVersion={msg.get('protocolVersion')!r}")
    if msg.get("messageType") != TYPE_CONTROLLER_AVAILABLE:
        problems.append(f"messageType={msg.get('messageType')!r}")
    if msg.get("requestId") != expected_request_id:
        problems.append(f"requestId={msg.get('requestId')!r}")

    if problems:
        return False, f"INVALID response from {from_addr}: {'; '.join(problems)} — raw={msg}", None

    description = (
        f"VALID CONTROLLER_AVAILABLE from {from_addr}: "
        f"name={msg.get('controllerName')!r} url={msg.get('controllerUrl')!r} "
        f"priority={msg.get('priority')!r} controllerId={msg.get('controllerId')!r}"
    )
    return True, description, msg


def select_best(candidates: list[dict]) -> dict | None:
    """Same tie-break as agent/src/discovery.rs::select_best: highest
    priority, then fastest response, then lexically smallest controller ID."""
    if not candidates:
        return None
    return min(
        candidates,
        key=lambda c: (-c["priority"], c["response_time"], c["controllerId"]),
    )


def do_listen_only(sock: socket.socket, port: int) -> None:
    sock.bind(("0.0.0.0", port))
    log(f"listening only on 0.0.0.0:{port} (Ctrl+C to stop)")
    sock.settimeout(None)
    try:
        while True:
            data, from_addr = sock.recvfrom(4096)
            log(f"packet from {from_addr} ({len(data)} bytes): {data!r}")
    except KeyboardInterrupt:
        pass


def do_broadcast(sock: socket.socket, port: int, timeout: float) -> list[dict]:
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.bind(("0.0.0.0", 0))
    payload, request_id, _ = build_request()
    start = time.monotonic()
    sent = sock.sendto(payload, ("255.255.255.255", port))
    log(f"sent DISCOVER_CONTROLLER ({sent} bytes) to 255.255.255.255:{port} requestId={request_id}")
    log(f"local socket bound to {sock.getsockname()}")
    return collect_responses(sock, request_id, timeout, start)


def do_sweep(sock: socket.socket, port: int, timeout: float, include_virtual: bool, max_hosts: int) -> list[dict]:
    networks = local_ipv4_networks(include_virtual)
    if not networks:
        log("no local IPv4 interfaces found to sweep (see warning above) — nothing to send")
        return []

    targets: list[str] = []
    for iface, iface_addr in networks:
        network = iface_addr.network
        host_count = network.num_addresses - 2 if network.num_addresses > 2 else network.num_addresses
        log(f"interface {iface}: {iface_addr} — subnet {network} ({host_count} host addresses)")
        if host_count > max_hosts:
            log(f"  subnet has {host_count} hosts, over --max-hosts={max_hosts}; skipping (pass a larger --max-hosts to include it)")
            continue
        for host in network.hosts():
            if host == iface_addr.ip:
                continue
            targets.append(str(host))

    if not targets:
        log("no sweep targets after filtering — nothing to send")
        return []

    sock.bind(("0.0.0.0", 0))
    payload, request_id, _ = build_request()
    log(f"sweeping {len(targets)} unicast target(s) on port {port}, requestId={request_id}")
    log(f"local socket bound to {sock.getsockname()}")

    start = time.monotonic()
    for target in targets:
        try:
            sock.sendto(payload, (target, port))
        except OSError as e:
            log(f"  send to {target} failed: {e}")
    log(f"sent {len(targets)} unicast probes in {time.monotonic() - start:.2f}s")

    return collect_responses(sock, request_id, timeout, start)


def collect_responses(sock: socket.socket, request_id: str, timeout: float, start: float) -> list[dict]:
    found = []
    deadline = start + timeout
    sock.settimeout(0.5)
    while time.monotonic() < deadline:
        try:
            data, from_addr = sock.recvfrom(4096)
        except socket.timeout:
            continue
        is_valid, description, msg = parse_response(data, from_addr, request_id)
        log(description)
        if is_valid:
            found.append({
                "controllerId": msg["controllerId"],
                "controllerName": msg.get("controllerName"),
                "controllerUrl": msg["controllerUrl"],
                "priority": msg.get("priority", 0),
                "response_time": time.monotonic() - start,
                "from_addr": from_addr,
            })
    return found


def send_fake_event(controller_url: str) -> None:
    device_id = str(uuid.uuid4())
    now = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")

    register_body = {
        "deviceId": device_id,
        "deviceName": HOSTNAME,
        "hostname": HOSTNAME,
        "agentVersion": "debug-probe",
        "operatingSystem": platform.system(),
        "operatingSystemVersion": platform.release(),
        "architecture": platform.machine(),
        "localIpAddresses": [addr for _, iface in local_ipv4_networks(include_virtual=False) for addr in [str(iface.ip)]],
    }

    log(f"registering {device_id} with {controller_url} ...")
    try:
        register_resp = post_json(f"{controller_url}/api/v1/agents/register", register_body)
    except (urllib.error.URLError, urllib.error.HTTPError) as e:
        log(f"registration failed: {e}")
        return
    log(f"registered: {register_resp}")

    confirmed_device_id = register_resp.get("deviceId", device_id)
    event_body = {
        "events": [
            {
                "eventId": str(uuid.uuid4()),
                "eventType": "AGENT_STARTED",
                "occurredAt": now,
                "severity": "INFO",
                "source": "AGENT",
                "sessionId": None,
                "username": None,
                "data": {"agentVersion": "debug-probe", "startReason": "DEBUG_PROBE_TEST_EVENT"},
            }
        ]
    }

    log(f"submitting 1 fake event to {controller_url} ...")
    try:
        events_resp = post_json(f"{controller_url}/api/v1/agents/{confirmed_device_id}/events", event_body)
    except (urllib.error.URLError, urllib.error.HTTPError) as e:
        log(f"event submission failed: {e}")
        return
    log(f"event submission result: {events_resp}")


def post_json(url: str, body: dict) -> dict:
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mode", choices=["sweep", "broadcast"], default="sweep",
                         help="discovery strategy (default: sweep — see module docstring for why)")
    parser.add_argument("--port", type=int, default=45821, help="discovery UDP port (default: 45821)")
    parser.add_argument("--timeout", type=float, default=5.0, help="seconds to wait for replies (default: 5)")
    parser.add_argument("--listen-only", action="store_true", help="don't send anything, just listen")
    parser.add_argument("--include-virtual", action="store_true",
                         help="sweep mode: also probe docker/veth/bridge/tunnel interfaces (default: excluded)")
    parser.add_argument("--max-hosts", type=int, default=1024,
                         help="sweep mode: skip any subnet with more host addresses than this (default: 1024)")
    parser.add_argument("--send-fake-event", action="store_true",
                         help="after discovery, register with the best-matching controller and submit one "
                              "synthetic AGENT_STARTED event to it")
    args = parser.parse_args()

    log(f"TimeLord discovery probe — hostname={HOSTNAME!r} mode={args.mode} port={args.port}")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    if args.listen_only:
        try:
            do_listen_only(sock, args.port)
        finally:
            sock.close()
        return

    if args.mode == "broadcast":
        found = do_broadcast(sock, args.port, args.timeout)
    else:
        found = do_sweep(sock, args.port, args.timeout, args.include_virtual, args.max_hosts)
    sock.close()

    if not found:
        log(
            "no valid responses received. If HTTP to the controller works but this doesn't, check: "
            "(1) a firewall on the controller host blocking inbound UDP on this port, "
            "(2) this machine and the controller are on the same L2 broadcast domain, "
            "(3) the switch/AP isn't filtering broadcast traffic, "
            "(4) if the controller runs in Docker, confirm 'docker info' shows EnableUserlandProxy=true "
            "and that the host firewall (ufw/firewalld) allows inbound udp on this port. "
            "If --mode broadcast finds nothing but --mode sweep does, the reply is being dropped on the way "
            "back through a broadcast-specific relay path (e.g. Docker's docker-proxy) — sweep sidesteps that."
        )
        return

    log(f"done — {len(found)} valid response(s) received")
    best = select_best(found)
    log(f"selected: {best['controllerName']!r} ({best['controllerUrl']})")

    if args.send_fake_event:
        send_fake_event(best["controllerUrl"])


if __name__ == "__main__":
    main()
