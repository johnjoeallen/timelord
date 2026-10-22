//! One request/response round trip to the agent service's pipe.

use std::io;

use timelord_ipc::{Request, Response};

#[cfg(windows)]
pub fn call(request: &Request) -> io::Result<Response> {
    use std::fs::OpenOptions;
    use std::io::{BufRead, BufReader, Write};
    use std::os::windows::fs::OpenOptionsExt;

    use timelord_ipc::PIPE_NAME;

    // The pipe's DACL grants ordinary users exactly this mask (read/write data, not
    // FILE_CREATE_PIPE_INSTANCE), and a generic GENERIC_WRITE open would ask for more than that.
    const CLIENT_ACCESS: u32 = 0x0012_008b;

    let mut pipe = OpenOptions::new().access_mode(CLIENT_ACCESS).open(PIPE_NAME)?;
    let mut request = serde_json::to_vec(request).map_err(io::Error::other)?;
    request.push(b'\n');
    pipe.write_all(&request)?;

    let mut line = String::new();
    BufReader::new(pipe).read_line(&mut line)?;
    serde_json::from_str(&line).map_err(io::Error::other)
}

/// The pipe is a Windows facility; on other systems there is no service to talk to.
#[cfg(not(windows))]
pub fn call(_request: &Request) -> io::Result<Response> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "the notifier only runs on Windows"))
}
