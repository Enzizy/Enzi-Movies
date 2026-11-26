import { useCallback, useEffect, useRef, useState } from "react";
import { PlayIcon } from "../components/Icons";
import { storage } from "../utils/storage";
import { androidRequest } from "./bridge";

export function AndroidPlayerButton({ url, title, progressKey, saveProgress, playRequest }) {
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState("");
  const inFlight = useRef(false);
  const handledRequest = useRef(0);

  useEffect(() => setError(""), [url]);

  const open = useCallback(async () => {
    if (!url || inFlight.current) return;
    inFlight.current = true;
    setOpening(true);
    setError("");
    try {
      const start = progressKey ? storage.get("dlTime_" + progressKey) || 0 : 0;
      const result = await androidRequest("play", { url, title, start });
      if (progressKey && result?.duration > 0) {
        storage.set("dlTime_" + progressKey, result.position / 1000);
        saveProgress?.(progressKey, Math.min(100, Math.round(100 * result.position / result.duration)));
      }
    } catch (error) {
      setError(error.message);
    } finally {
      inFlight.current = false;
      setOpening(false);
    }
  }, [url, title, progressKey, saveProgress]);

  useEffect(() => {
    if (!url || playRequest === handledRequest.current) return;
    handledRequest.current = playRequest;
    void open();
  }, [url, playRequest, open]);

  return (
    <div className="android-player-launch">
      <button className="android-primary" onClick={open} disabled={!url || opening}>
        <PlayIcon /> {opening ? "Playing" : "Play"}
      </button>
      {error && <p role="alert">{error}</p>}
    </div>
  );
}

export function AndroidMedia() {
  const [url, setUrl] = useState("");
  const [items, setItems] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function refresh() {
    setItems(await androidRequest("downloads"));
  }

  useEffect(() => {
    let active = true;
    const update = () => androidRequest("downloads")
      .then((items) => { if (active) setItems(items); })
      .catch((failure) => { if (active) setError(failure.message); });
    update();
    const timer = window.setInterval(update, 2000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  async function run(op, args) {
    setBusy(true);
    setError("");
    try {
      await androidRequest(op, args);
      if (op === "download" || op === "removeDownload") await refresh();
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="android-panel">
      <h1>Downloads</h1>
      <div className="android-card">
        <label htmlFor="android-media-url">Media URL</label>
        <input id="android-media-url" inputMode="url" placeholder="https://…/video.mp4"
          value={url} onChange={(event) => setUrl(event.target.value)} />
        <div className="android-actions">
          <button className="android-primary" disabled={busy || !url.trim()}
            onClick={() => run("play", { url })}>Play link</button>
          <button disabled={busy || !url.trim()} onClick={() => run("download", { url })}>Download file</button>
          <button disabled={busy} onClick={() => run("pickMedia")}>Open local video</button>
        </div>
      </div>
      {error && <p role="alert">{error}</p>}
      <h2>Saved on this device</h2>
      {items.length === 0 && <p>No downloads yet.</p>}
      {items.map((item) => (
        <article className="android-card" key={item.id}>
          <strong>{item.name}</strong>
          <span>{item.status}</span>
          {item.status === "downloading" && item.total > 0 && (
            <progress aria-label="Download progress" value={item.received} max={item.total} />
          )}
          <div className="android-actions">
            {item.status === "complete" && (
              <button className="android-primary" disabled={busy}
                onClick={() => run("playDownload", { downloadId: item.id })}>Play</button>
            )}
            <button disabled={busy} onClick={() => {
              if (window.confirm(`Remove ${item.name}?`)) run("removeDownload", { downloadId: item.id });
            }}>Remove</button>
          </div>
        </article>
      ))}
    </section>
  );
}

export function AndroidSettings({ apiKey, onChangeApiKey }) {
  return (
    <section className="android-panel">
      <h1>Settings</h1>
      <div className="android-card">
        <h2>TMDB Read Access Token</h2>
        <button className="android-primary" onClick={onChangeApiKey}>
          {apiKey ? "Change token" : "Add token"}
        </button>
      </div>
    </section>
  );
}
