//! HTTP client for the Phase-1 agent-facing controller API
//! (`/api/v1/agents/...`, `/api/v1/system/info`). Plain HTTP, no
//! authentication or signing yet — see [`RequestSigner`] for the seam a
//! later security phase hooks into.

use std::time::Duration;

use uuid::Uuid;

use crate::protocol::{
    EventSubmissionRequest, EventSubmissionResponse, HeartbeatRequest, HeartbeatResponse, RegisterRequest,
    RegisterResponse, SystemInfoResponse,
};

const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, thiserror::Error)]
pub enum ClientError {
    #[error("request error: {0}")]
    Request(#[from] reqwest::Error),
    #[error("controller returned HTTP {status}: {body}")]
    ApiError { status: u16, body: String },
}

impl ClientError {
    /// True when the controller rejected a request because it no longer
    /// (or never did) recognise this device — the agent should re-register
    /// (design brief section 17).
    pub fn is_device_not_found(&self) -> bool {
        matches!(self, ClientError::ApiError { status: 404, .. })
    }
}

/// Signs an outgoing request body. Phase 1 ships only [`NoOpRequestSigner`];
/// a later security phase adds Ed25519 agent identity behind this same
/// trait without the client call sites needing to change.
pub trait RequestSigner: Send + Sync {
    fn sign(&self, body: &[u8]) -> anyhow::Result<Option<SignatureHeaders>>;
}

#[derive(Debug, Clone)]
pub struct SignatureHeaders {
    pub headers: Vec<(String, String)>,
}

pub struct NoOpRequestSigner;

impl RequestSigner for NoOpRequestSigner {
    fn sign(&self, _body: &[u8]) -> anyhow::Result<Option<SignatureHeaders>> {
        Ok(None)
    }
}

pub struct ControllerClient {
    http: reqwest::Client,
    base_url: String,
    #[allow(dead_code)] // wired through once a real signer exists
    signer: Box<dyn RequestSigner>,
}

impl ControllerClient {
    pub fn new(base_url: impl Into<String>) -> Self {
        Self::with_signer(base_url, Box::new(NoOpRequestSigner))
    }

    pub fn with_signer(base_url: impl Into<String>, signer: Box<dyn RequestSigner>) -> Self {
        let http = reqwest::Client::builder().timeout(REQUEST_TIMEOUT).build().expect("reqwest client builds");
        Self { http, base_url: base_url.into().trim_end_matches('/').to_string(), signer }
    }

    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    pub async fn system_info(&self) -> Result<SystemInfoResponse, ClientError> {
        let response = self.http.get(format!("{}/api/v1/system/info", self.base_url)).send().await?;
        Self::parse(response).await
    }

    pub async fn register(&self, request: &RegisterRequest) -> Result<RegisterResponse, ClientError> {
        let response = self.http.post(format!("{}/api/v1/agents/register", self.base_url)).json(request).send().await?;
        Self::parse(response).await
    }

    pub async fn heartbeat(&self, device_id: Uuid, request: &HeartbeatRequest) -> Result<HeartbeatResponse, ClientError> {
        let response = self
            .http
            .post(format!("{}/api/v1/agents/{device_id}/heartbeat", self.base_url))
            .json(request)
            .send()
            .await?;
        Self::parse(response).await
    }

    pub async fn submit_events(
        &self,
        device_id: Uuid,
        request: &EventSubmissionRequest,
    ) -> Result<EventSubmissionResponse, ClientError> {
        let response =
            self.http.post(format!("{}/api/v1/agents/{device_id}/events", self.base_url)).json(request).send().await?;
        Self::parse(response).await
    }

    async fn parse<T: serde::de::DeserializeOwned>(response: reqwest::Response) -> Result<T, ClientError> {
        let status = response.status();
        if status.is_success() {
            Ok(response.json::<T>().await?)
        } else {
            let body = response.text().await.unwrap_or_default();
            Err(ClientError::ApiError { status: status.as_u16(), body })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn device_not_found_detection() {
        let not_found = ClientError::ApiError { status: 404, body: String::new() };
        assert!(not_found.is_device_not_found());

        let other = ClientError::ApiError { status: 500, body: String::new() };
        assert!(!other.is_device_not_found());
    }

    #[test]
    fn no_op_signer_never_signs() {
        let signer = NoOpRequestSigner;
        assert!(signer.sign(b"anything").unwrap().is_none());
    }
}
