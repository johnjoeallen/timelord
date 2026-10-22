//! A clock the person at the keyboard can't move.
//!
//! The schedule is absolute UTC instants, so it is only as right as the time the agent compares it
//! with, and Windows lets a user change the system clock. Enforcement therefore uses
//! [`TrustedClock`]: the system clock plus an offset measured against NTP servers (SNTP, RFC 4330),
//! re-measured regularly and whenever the system clock appears to have jumped. The offset is kept
//! while NTP is unreachable, so blocking it only freezes the last measurement.

use std::net::{ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::time::Duration;

use anyhow::{anyhow, bail, Context};
use chrono::{DateTime, Utc};

/// Used when `agent.toml` doesn't list any. Port 123 is added if a server has none.
pub const DEFAULT_NTP_SERVERS: [&str; 3] =
    ["time.cloudflare.com", "time.windows.com", "pool.ntp.org"];

/// How long to wait for each server's answer.
const QUERY_TIMEOUT: Duration = Duration::from_secs(2);
/// A round trip slower than this says little about the offset; the sample is thrown away.
const MAX_ROUND_TRIP_MS: i64 = 3_000;
/// Seconds between the NTP epoch (1900) and the Unix epoch (1970).
const NTP_UNIX_DELTA: i64 = 2_208_988_800;

/// System time plus the offset last measured against NTP.
pub struct TrustedClock {
    enabled: bool,
    offset_ms: AtomicI64,
    synced: AtomicBool,
}

impl TrustedClock {
    pub fn new(enabled: bool) -> Self {
        Self {
            enabled,
            offset_ms: AtomicI64::new(0),
            synced: AtomicBool::new(false),
        }
    }

    pub fn enabled(&self) -> bool {
        self.enabled
    }

    /// The real time, as best the last NTP measurement says (just the system clock if NTP is off).
    pub fn now(&self) -> DateTime<Utc> {
        Utc::now() + chrono::Duration::milliseconds(self.offset_ms())
    }

    /// How far the system clock is behind (positive) or ahead of (negative) the real time.
    pub fn offset_ms(&self) -> i64 {
        self.offset_ms.load(Ordering::Relaxed)
    }

    pub fn set_offset_ms(&self, offset_ms: i64) {
        self.offset_ms.store(offset_ms, Ordering::Relaxed);
        self.synced.store(true, Ordering::Relaxed);
    }

    /// Whether the offset is worth acting on: measured at least once, or NTP is switched off.
    pub fn is_ready(&self) -> bool {
        !self.enabled || self.synced.load(Ordering::Relaxed)
    }
}

/// Offset of the local clock from the server's: `((t2 - t1) + (t3 - t4)) / 2`, where t1 is the local
/// send time, t2 the server's receive time, t3 its transmit time and t4 the local receive time.
pub fn offset_ms(t1: i64, t2: i64, t3: i64, t4: i64) -> i64 {
    ((t2 - t1) + (t3 - t4)) / 2
}

/// Network round trip, excluding the time the server spent on the request.
fn delay_ms(t1: i64, t2: i64, t3: i64, t4: i64) -> i64 {
    (t4 - t1) - (t3 - t2)
}

/// Unix milliseconds to an NTP timestamp (seconds since 1900, 32-bit fraction).
fn to_ntp(unix_ms: i64) -> [u8; 8] {
    let secs = (unix_ms.div_euclid(1000) + NTP_UNIX_DELTA) as u32;
    let frac = ((unix_ms.rem_euclid(1000) as u64) << 32) / 1000;
    let mut out = [0u8; 8];
    out[..4].copy_from_slice(&secs.to_be_bytes());
    out[4..].copy_from_slice(&(frac as u32).to_be_bytes());
    out
}

/// An NTP timestamp to Unix milliseconds. Seconds with the top bit clear are in NTP era 1 (from 2036).
fn from_ntp(bytes: &[u8]) -> i64 {
    let secs = u32::from_be_bytes(bytes[..4].try_into().unwrap()) as i64;
    let frac = u32::from_be_bytes(bytes[4..8].try_into().unwrap()) as i64;
    let secs = if secs & 0x8000_0000 == 0 {
        secs + (1 << 32)
    } else {
        secs
    };
    (secs - NTP_UNIX_DELTA) * 1000 + ((frac * 1000) >> 32)
}

fn request_packet(t1_ms: i64) -> [u8; 48] {
    let mut packet = [0u8; 48];
    packet[0] = 0x23; // no leap warning, version 4, client mode
    packet[40..48].copy_from_slice(&to_ntp(t1_ms));
    packet
}

/// Checks a reply and returns the server's (receive, transmit) times in Unix milliseconds.
fn parse_reply(reply: &[u8], request: &[u8; 48]) -> anyhow::Result<(i64, i64)> {
    if reply.len() < 48 {
        bail!("short NTP reply ({} bytes)", reply.len());
    }
    if reply[0] & 7 != 4 {
        bail!("not a server-mode reply");
    }
    if reply[0] >> 6 == 3 {
        bail!("server clock is not synchronised");
    }
    if !(1..=15).contains(&reply[1]) {
        bail!(
            "unusable stratum {} (kiss-o'-death or unsynchronised)",
            reply[1]
        );
    }
    // The server must echo back our transmit time, or this isn't an answer to our request.
    if reply[24..32] != request[40..48] {
        bail!("reply does not match the request");
    }
    if reply[40..48].iter().all(|b| *b == 0) {
        bail!("reply has no transmit time");
    }
    Ok((from_ntp(&reply[32..40]), from_ntp(&reply[40..48])))
}

/// Asks one server for the time; blocking. Returns the local clock's offset in milliseconds.
pub fn query(server: &str, timeout: Duration) -> anyhow::Result<i64> {
    let target = if server.contains(':') {
        server.to_string()
    } else {
        format!("{server}:123")
    };
    let addr = target
        .to_socket_addrs()
        .with_context(|| format!("resolving {target}"))?
        .next()
        .ok_or_else(|| anyhow!("{target} did not resolve"))?;
    let socket = UdpSocket::bind(if addr.is_ipv4() {
        "0.0.0.0:0"
    } else {
        "[::]:0"
    })?;
    socket.set_read_timeout(Some(timeout))?;
    socket.set_write_timeout(Some(timeout))?;

    let t1 = Utc::now().timestamp_millis();
    let request = request_packet(t1);
    socket.send_to(&request, addr)?;
    let mut reply = [0u8; 128];
    let (len, from) = socket.recv_from(&mut reply)?;
    let t4 = Utc::now().timestamp_millis();
    if from.ip() != addr.ip() {
        bail!("reply came from {from}, not {addr}");
    }

    let (t2, t3) = parse_reply(&reply[..len], &request)?;
    let delay = delay_ms(t1, t2, t3, t4);
    if !(0..=MAX_ROUND_TRIP_MS).contains(&delay) {
        bail!("round trip of {delay} ms is too slow to trust");
    }
    Ok(offset_ms(t1, t2, t3, t4))
}

/// Queries every server and returns the median offset of the ones that answered, so a single wrong
/// server can't move the clock. `None` if none answered.
pub fn measure(servers: &[String], timeout: Duration) -> Option<i64> {
    let mut offsets: Vec<i64> = servers
        .iter()
        .filter_map(|server| match query(server, timeout) {
            Ok(offset) => Some(offset),
            Err(err) => {
                tracing::debug!(%server, ?err, "NTP query failed");
                None
            }
        })
        .collect();
    median(&mut offsets)
}

fn median(values: &mut [i64]) -> Option<i64> {
    if values.is_empty() {
        return None;
    }
    values.sort_unstable();
    let mid = values.len() / 2;
    Some(if values.len() % 2 == 1 {
        values[mid]
    } else {
        (values[mid - 1] + values[mid]) / 2
    })
}

/// [`measure`] with the default timeout, for callers that only have the server list.
pub fn measure_default(servers: &[String]) -> Option<i64> {
    measure(servers, QUERY_TIMEOUT)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ntp_timestamps_round_trip_through_unix_milliseconds() {
        for ms in [0, 1_000, 1_792_700_000_123, 1_792_700_000_999] {
            let back = from_ntp(&to_ntp(ms));
            assert!((back - ms).abs() <= 1, "{ms} came back as {back}");
        }
    }

    #[test]
    fn a_known_ntp_timestamp_is_the_expected_unix_time() {
        // 2026-10-22T19:00:00Z is 1_792_695_600 Unix seconds.
        let ntp = to_ntp(1_792_695_600_000);
        assert_eq!(
            u32::from_be_bytes(ntp[..4].try_into().unwrap()) as i64,
            1_792_695_600 + NTP_UNIX_DELTA
        );
    }

    #[test]
    fn offset_is_zero_for_a_matching_clock_and_signed_otherwise() {
        // Symmetric 20 ms path, server and client agree.
        assert_eq!(offset_ms(1_000, 1_010, 1_010, 1_020), 0);
        // Local clock an hour slow: the server reads an hour later than we do.
        assert_eq!(offset_ms(1_000, 3_601_010, 3_601_010, 1_020), 3_600_000);
        // Local clock an hour fast.
        assert_eq!(offset_ms(3_601_000, 1_010, 1_010, 3_601_020), -3_600_000);
        assert_eq!(delay_ms(1_000, 1_010, 1_015, 1_025), 20);
    }

    fn reply_for(request: &[u8; 48], t2: i64, t3: i64) -> [u8; 48] {
        let mut reply = [0u8; 48];
        reply[0] = 0x24; // version 4, server mode
        reply[1] = 2;
        reply[24..32].copy_from_slice(&request[40..48]);
        reply[32..40].copy_from_slice(&to_ntp(t2));
        reply[40..48].copy_from_slice(&to_ntp(t3));
        reply
    }

    #[test]
    fn a_valid_reply_is_accepted_and_bad_ones_are_refused() {
        let request = request_packet(1_792_695_600_000);
        let good = reply_for(&request, 1_792_695_600_010, 1_792_695_600_011);
        let (t2, t3) = parse_reply(&good, &request).unwrap();
        assert!((t2 - 1_792_695_600_010).abs() <= 1 && (t3 - 1_792_695_600_011).abs() <= 1);

        let mut wrong_mode = good;
        wrong_mode[0] = 0x23;
        assert!(parse_reply(&wrong_mode, &request).is_err());

        let mut unsynchronised = good;
        unsynchronised[0] |= 0xC0;
        assert!(parse_reply(&unsynchronised, &request).is_err());

        let mut kiss_of_death = good;
        kiss_of_death[1] = 0;
        assert!(parse_reply(&kiss_of_death, &request).is_err());

        let other_request = request_packet(1_792_695_700_000);
        assert!(
            parse_reply(&good, &other_request).is_err(),
            "an answer to a different request is spoofed or stale"
        );
        assert!(parse_reply(&good[..20], &request).is_err());
    }

    #[test]
    fn the_median_ignores_one_wrong_server() {
        assert_eq!(median(&mut [5, 3_600_000, 7]), Some(7));
        assert_eq!(median(&mut [10, 20]), Some(15));
        assert_eq!(median(&mut []), None);
    }

    #[test]
    fn query_measures_the_offset_against_a_server_that_is_an_hour_ahead() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        let addr = server.local_addr().unwrap();
        let handle = std::thread::spawn(move || {
            let mut buf = [0u8; 48];
            let (_, from) = server.recv_from(&mut buf).unwrap();
            let ahead = Utc::now().timestamp_millis() + 3_600_000;
            let reply = reply_for(&buf, ahead, ahead);
            server.send_to(&reply, from).unwrap();
        });

        let offset = query(&addr.to_string(), Duration::from_secs(2)).unwrap();

        handle.join().unwrap();
        assert!((offset - 3_600_000).abs() < 500, "offset was {offset}");
    }

    #[test]
    fn query_times_out_on_a_silent_server() {
        let silent = UdpSocket::bind("127.0.0.1:0").unwrap();
        let addr = silent.local_addr().unwrap();
        assert!(query(&addr.to_string(), Duration::from_millis(200)).is_err());
    }

    #[test]
    fn the_trusted_clock_applies_the_offset_and_reports_readiness() {
        let clock = TrustedClock::new(true);
        assert!(!clock.is_ready(), "not ready before the first measurement");

        clock.set_offset_ms(3_600_000);

        assert!(clock.is_ready());
        let ahead = clock.now() - Utc::now();
        assert!((ahead.num_milliseconds() - 3_600_000).abs() < 1_000);
        assert!(
            TrustedClock::new(false).is_ready(),
            "with NTP off there is nothing to wait for"
        );
    }

    /// Needs network access to public NTP servers: `cargo test -- --ignored real_ntp`.
    #[test]
    #[ignore]
    fn real_ntp_servers_agree_with_this_machine() {
        let servers: Vec<String> = DEFAULT_NTP_SERVERS.iter().map(|s| s.to_string()).collect();
        for server in &servers {
            println!("{server}: {:?}", query(server, Duration::from_secs(3)));
        }
        let offset =
            measure(&servers, Duration::from_secs(3)).expect("at least one NTP server answers");
        println!("median offset: {offset} ms");
        assert!(
            offset.abs() < 5_000,
            "this machine's clock is {offset} ms off"
        );
    }
}
