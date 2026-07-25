//! UDP controller discovery (design brief section 4). Active discovery
//! (broadcast a `DISCOVER_CONTROLLER`, wait for `CONTROLLER_AVAILABLE`
//! replies) is the primary mechanism; passive `CONTROLLER_ANNOUNCEMENT`
//! listening is not implemented yet (Phase 1 minimum: active + explicit URL).

use std::net::SocketAddr;
use std::time::{Duration, Instant};

use anyhow::Context;
use serde::{Deserialize, Serialize};
use tokio::net::UdpSocket;
use tokio::time::timeout;
use tracing::{debug, warn};
use uuid::Uuid;

const PROTOCOL_NAME: &str = "timelord-discovery";
const PROTOCOL_VERSION: u32 = 1;
const TYPE_DISCOVER_CONTROLLER: &str = "DISCOVER_CONTROLLER";
const TYPE_CONTROLLER_AVAILABLE: &str = "CONTROLLER_AVAILABLE";
const MAX_PACKET_SIZE: usize = 4096;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DiscoverRequestMessage {
    protocol: &'static str,
    protocol_version: u32,
    message_type: &'static str,
    request_id: Uuid,
    agent_version: String,
    device_id: Uuid,
    hostname: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ControllerAvailableMessage {
    protocol: String,
    protocol_version: u32,
    message_type: String,
    request_id: Uuid,
    controller_id: Uuid,
    controller_name: String,
    controller_url: String,
    priority: i32,
}

/// A validated `CONTROLLER_AVAILABLE` response, plus how long it took to
/// arrive (used as a tie-breaker when several controllers reply).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredController {
    pub controller_id: Uuid,
    pub controller_name: String,
    pub controller_url: String,
    pub priority: i32,
    pub response_time: Duration,
}

/// Parses and validates a raw UDP payload as a `CONTROLLER_AVAILABLE`
/// response to `expected_request_id`. Rejects anything that doesn't match
/// the protocol name/version/message type/request ID, or whose
/// `controllerUrl` isn't a plausible `http(s)://` URL — see design brief
/// section 4 ("The agent must verify...").
fn validate_response(
    payload: &[u8],
    expected_request_id: Uuid,
    response_time: Duration,
) -> Option<DiscoveredController> {
    let message: ControllerAvailableMessage = serde_json::from_slice(payload).ok()?;
    if message.protocol != PROTOCOL_NAME {
        return None;
    }
    if message.protocol_version != PROTOCOL_VERSION {
        return None;
    }
    if message.message_type != TYPE_CONTROLLER_AVAILABLE {
        return None;
    }
    if message.request_id != expected_request_id {
        return None;
    }
    if !(message.controller_url.starts_with("http://") || message.controller_url.starts_with("https://")) {
        return None;
    }

    Some(DiscoveredController {
        controller_id: message.controller_id,
        controller_name: message.controller_name,
        controller_url: message.controller_url,
        priority: message.priority,
        response_time,
    })
}

/// Selects the best candidate per design brief section 4: highest
/// `priority`, then fastest response, then lexically smallest controller ID
/// as a deterministic tie-breaker.
pub fn select_best(candidates: &[DiscoveredController]) -> Option<&DiscoveredController> {
    candidates.iter().min_by(|a, b| {
        b.priority
            .cmp(&a.priority)
            .then(a.response_time.cmp(&b.response_time))
            .then(a.controller_id.to_string().cmp(&b.controller_id.to_string()))
    })
}

/// Broadcasts a `DISCOVER_CONTROLLER` request and collects every valid
/// response received within `discovery_timeout`, logging each one (design
/// brief: "Log all responding controllers"). Does not itself pick a winner
/// — call [`select_best`] on the result.
pub async fn discover(
    device_id: Uuid,
    agent_version: &str,
    hostname: &str,
    discovery_port: u16,
    discovery_timeout: Duration,
) -> anyhow::Result<Vec<DiscoveredController>> {
    let socket = UdpSocket::bind(("0.0.0.0", 0)).await.context("binding discovery socket")?;
    socket.set_broadcast(true).context("enabling SO_BROADCAST")?;

    let request_id = Uuid::new_v4();
    let request = DiscoverRequestMessage {
        protocol: PROTOCOL_NAME,
        protocol_version: PROTOCOL_VERSION,
        message_type: TYPE_DISCOVER_CONTROLLER,
        request_id,
        agent_version: agent_version.to_string(),
        device_id,
        hostname: hostname.to_string(),
    };
    let payload = serde_json::to_vec(&request)?;

    let broadcast_addr: SocketAddr = ([255, 255, 255, 255], discovery_port).into();
    socket.send_to(&payload, broadcast_addr).await.context("sending discovery broadcast")?;
    debug!(%request_id, port = discovery_port, "sent DISCOVER_CONTROLLER broadcast");

    let start = Instant::now();
    let mut found = Vec::new();
    let mut buf = [0u8; MAX_PACKET_SIZE];

    let deadline = start + discovery_timeout;
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        match timeout(remaining, socket.recv_from(&mut buf)).await {
            Ok(Ok((len, from))) => {
                let elapsed = start.elapsed();
                match validate_response(&buf[..len], request_id, elapsed) {
                    Some(controller) => {
                        debug!(controller_id = %controller.controller_id, name = %controller.controller_name,
                               url = %controller.controller_url, priority = controller.priority,
                               from = %from, "controller responded to discovery");
                        found.push(controller);
                    }
                    None => warn!(from = %from, "ignoring invalid/unsupported discovery response"),
                }
            }
            Ok(Err(err)) => {
                warn!(?err, "error receiving discovery response");
                break;
            }
            Err(_elapsed) => break, // timed out waiting for the next packet
        }
    }

    Ok(found)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_response_json(request_id: Uuid) -> String {
        format!(
            r#"{{
                "protocol": "timelord-discovery",
                "protocolVersion": 1,
                "messageType": "CONTROLLER_AVAILABLE",
                "requestId": "{request_id}",
                "controllerId": "5872d46f-8c22-411f-9184-6ec069b85370",
                "controllerName": "Home TimeLord",
                "controllerUrl": "http://192.168.1.20:8080",
                "priority": 100
            }}"#
        )
    }

    #[test]
    fn valid_response_is_accepted() {
        let request_id = Uuid::new_v4();
        let json = sample_response_json(request_id);
        let result = validate_response(json.as_bytes(), request_id, Duration::from_millis(5));
        assert!(result.is_some());
        let controller = result.unwrap();
        assert_eq!(controller.controller_name, "Home TimeLord");
        assert_eq!(controller.priority, 100);
    }

    #[test]
    fn mismatched_request_id_is_rejected() {
        let json = sample_response_json(Uuid::new_v4());
        let result = validate_response(json.as_bytes(), Uuid::new_v4(), Duration::from_millis(5));
        assert!(result.is_none());
    }

    #[test]
    fn wrong_protocol_name_is_rejected() {
        let request_id = Uuid::new_v4();
        let json = sample_response_json(request_id).replace("timelord-discovery", "some-other-protocol");
        assert!(validate_response(json.as_bytes(), request_id, Duration::from_millis(5)).is_none());
    }

    #[test]
    fn unsupported_protocol_version_is_rejected() {
        let request_id = Uuid::new_v4();
        let json = sample_response_json(request_id).replace("\"protocolVersion\": 1", "\"protocolVersion\": 999");
        assert!(validate_response(json.as_bytes(), request_id, Duration::from_millis(5)).is_none());
    }

    #[test]
    fn wrong_message_type_is_rejected() {
        let request_id = Uuid::new_v4();
        let json = sample_response_json(request_id).replace("CONTROLLER_AVAILABLE", "CONTROLLER_ANNOUNCEMENT");
        assert!(validate_response(json.as_bytes(), request_id, Duration::from_millis(5)).is_none());
    }

    #[test]
    fn non_http_controller_url_is_rejected() {
        let request_id = Uuid::new_v4();
        let json = sample_response_json(request_id).replace("http://192.168.1.20:8080", "not-a-url");
        assert!(validate_response(json.as_bytes(), request_id, Duration::from_millis(5)).is_none());
    }

    #[test]
    fn malformed_json_is_rejected() {
        assert!(validate_response(b"not json at all", Uuid::new_v4(), Duration::from_millis(5)).is_none());
    }

    fn controller(id: &str, priority: i32, response_ms: u64) -> DiscoveredController {
        DiscoveredController {
            controller_id: Uuid::parse_str(id).unwrap(),
            controller_name: "Test".into(),
            controller_url: "http://example.invalid".into(),
            priority,
            response_time: Duration::from_millis(response_ms),
        }
    }

    #[test]
    fn selects_highest_priority() {
        let candidates = vec![
            controller("00000000-0000-0000-0000-000000000001", 50, 10),
            controller("00000000-0000-0000-0000-000000000002", 100, 20),
        ];
        let best = select_best(&candidates).unwrap();
        assert_eq!(best.priority, 100);
    }

    #[test]
    fn ties_on_priority_break_by_fastest_response() {
        let candidates = vec![
            controller("00000000-0000-0000-0000-000000000001", 100, 50),
            controller("00000000-0000-0000-0000-000000000002", 100, 10),
        ];
        let best = select_best(&candidates).unwrap();
        assert_eq!(best.response_time, Duration::from_millis(10));
    }

    #[test]
    fn ties_on_priority_and_speed_break_by_lexically_smallest_id() {
        let candidates = vec![
            controller("bbbbbbbb-0000-0000-0000-000000000000", 100, 10),
            controller("aaaaaaaa-0000-0000-0000-000000000000", 100, 10),
        ];
        let best = select_best(&candidates).unwrap();
        assert_eq!(best.controller_id.to_string(), "aaaaaaaa-0000-0000-0000-000000000000");
    }

    #[test]
    fn empty_candidates_selects_nothing() {
        assert!(select_best(&[]).is_none());
    }

    #[tokio::test]
    async fn discover_receives_and_validates_a_real_udp_response() {
        // A minimal stand-in "controller" bound to loopback: waits for one
        // request, echoes its requestId back in a CONTROLLER_AVAILABLE.
        // `discover()` itself broadcasts to 255.255.255.255, which won't
        // reach a loopback-bound responder (and broadcast permissions vary
        // in CI sandboxes), so this exercises the same send/receive/validate
        // path directly over a unicast loopback round trip instead.
        let responder = UdpSocket::bind(("127.0.0.1", 0)).await.unwrap();
        let responder_port = responder.local_addr().unwrap().port();
        let responder_task = tokio::spawn(async move {
            let mut buf = [0u8; MAX_PACKET_SIZE];
            let (len, from) = responder.recv_from(&mut buf).await.unwrap();
            let request: serde_json::Value = serde_json::from_slice(&buf[..len]).unwrap();
            let request_id = request["requestId"].as_str().unwrap();
            let response = format!(
                r#"{{
                    "protocol": "timelord-discovery",
                    "protocolVersion": 1,
                    "messageType": "CONTROLLER_AVAILABLE",
                    "requestId": "{request_id}",
                    "controllerId": "5872d46f-8c22-411f-9184-6ec069b85370",
                    "controllerName": "Loopback Controller",
                    "controllerUrl": "http://127.0.0.1:8080",
                    "priority": 100
                }}"#
            );
            responder.send_to(response.as_bytes(), from).await.unwrap();
        });

        let socket = UdpSocket::bind(("0.0.0.0", 0)).await.unwrap();
        let request_id = Uuid::new_v4();
        let request = DiscoverRequestMessage {
            protocol: PROTOCOL_NAME,
            protocol_version: PROTOCOL_VERSION,
            message_type: TYPE_DISCOVER_CONTROLLER,
            request_id,
            agent_version: "0.1.0".into(),
            device_id: Uuid::new_v4(),
            hostname: "test-pc".into(),
        };
        socket
            .send_to(&serde_json::to_vec(&request).unwrap(), ("127.0.0.1", responder_port))
            .await
            .unwrap();

        let mut buf = [0u8; MAX_PACKET_SIZE];
        let (len, _from) = timeout(Duration::from_secs(2), socket.recv_from(&mut buf)).await.unwrap().unwrap();
        let result = validate_response(&buf[..len], request_id, Duration::from_millis(1));
        assert!(result.is_some());
        assert_eq!(result.unwrap().controller_name, "Loopback Controller");

        responder_task.await.unwrap();
    }
}
