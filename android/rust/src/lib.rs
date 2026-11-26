use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::{json, Value};
use std::io::Read;
use std::sync::OnceLock;
use std::time::Duration;
use url::Url;

const TMDB_HOST: &str = "api.themoviedb.org";
const MAX_RESPONSE: u64 = 8 * 1024 * 1024;

fn tmdb_url(path: &str) -> Result<Url, &'static str> {
    if !path.starts_with('/') || path.starts_with("//") {
        return Err("Invalid TMDB path");
    }
    let url =
        Url::parse(&format!("https://{TMDB_HOST}/3{path}")).map_err(|_| "Invalid TMDB URL")?;
    if url.host_str() != Some(TMDB_HOST)
        || !url.path().starts_with("/3/")
        || url.fragment().is_some()
    {
        return Err("Invalid TMDB endpoint");
    }
    Ok(url)
}

fn tmdb(path: &str, token: &str) -> Result<Value, &'static str> {
    let url = tmdb_url(path)?;
    if token.is_empty() || token.len() > 4096 || token.bytes().any(|b| !b.is_ascii_graphic()) {
        return Ok(json!({ "status": 401, "body": null }));
    }
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    let agent = AGENT.get_or_init(|| {
        ureq::AgentBuilder::new()
            .redirects(0)
            .timeout(Duration::from_secs(12))
            .build()
    });
    let response = match agent
        .get(url.as_str())
        .set("Authorization", &format!("Bearer {token}"))
        .set("Accept", "application/json")
        .call()
    {
        Ok(response) | Err(ureq::Error::Status(_, response)) => response,
        Err(_) => return Err("TMDB is unreachable"),
    };
    let status = response.status();
    if !(200..300).contains(&status) {
        return Ok(json!({ "status": status, "body": null }));
    }
    let mut bytes = Vec::new();
    response
        .into_reader()
        .take(MAX_RESPONSE + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "Failed to read TMDB response")?;
    if bytes.len() as u64 > MAX_RESPONSE {
        return Err("TMDB response is too large");
    }
    let body: Value = serde_json::from_slice(&bytes).map_err(|_| "Invalid TMDB response")?;
    Ok(json!({ "status": status, "body": body }))
}

fn media(input: &str) -> Result<Value, &'static str> {
    let url = Url::parse(input.trim()).map_err(|_| "Enter a valid HTTPS media URL")?;
    if url.scheme() != "https"
        || !url.has_host()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("Enter a valid HTTPS media URL");
    }
    let path = url.path().to_ascii_lowercase();
    let kind = if path.ends_with(".m3u8") {
        "hls"
    } else if [
        ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".mp3", ".aac", ".ogg",
    ]
    .iter()
    .any(|extension| path.ends_with(extension))
    {
        "file"
    } else {
        "page"
    };
    Ok(json!({ "url": url.as_str(), "kind": kind }))
}

fn dispatch(input: &str) -> Result<Value, &'static str> {
    let request: Value = serde_json::from_str(input).map_err(|_| "Invalid native request")?;
    let arg = |name: &str| request[name].as_str().unwrap_or_default();
    match arg("op") {
        "tmdb" => tmdb(arg("path"), arg("token")),
        "media" => media(arg("url")),
        _ => Err("Unsupported native request"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_truelockmc_streambert_NativeCore_call(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let output = match env.get_string(&input) {
        Ok(input) => match dispatch(&String::from(input)) {
            Ok(value) => json!({ "value": value }),
            Err(error) => json!({ "error": error }),
        },
        Err(_) => return std::ptr::null_mut(),
    };
    env.new_string(output.to_string())
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn credentials_stay_on_tmdb_api_paths() {
        assert!(tmdb_url("/search/multi?query=a%2Fb").is_ok());
        for path in [
            "//example.com",
            "/../../other",
            "/%2e%2e/other",
            "/movie#fragment",
        ] {
            assert!(tmdb_url(path).is_err(), "{path}");
        }
    }

    #[test]
    fn distinguishes_media_files_from_provider_pages() {
        assert_eq!(
            media("https://example.com/a.M3U8?token=x").unwrap()["kind"],
            "hls"
        );
        assert_eq!(
            media("https://example.com/a.mp4#x").unwrap()["kind"],
            "file"
        );
        assert_eq!(
            media("https://example.com/embed/movie/1").unwrap()["kind"],
            "page"
        );
        for url in [
            "file:///private/x.mp4",
            "https://name@example.com/a.mp4",
            "http://example.com",
        ] {
            assert!(media(url).is_err(), "{url}");
        }
    }
}
