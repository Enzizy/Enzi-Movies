export const isAndroid = import.meta.env.VITE_STREAMBERT_ANDROID === "1";

const bridge = isAndroid ? window.StreambertAndroid : null;
const pending = new Map();
let nextId = 0;

export function androidRequest(op, args = {}) {
  return new Promise((resolve, reject) => {
    if (!bridge) {
      reject(new Error("Android bridge is unavailable"));
      return;
    }
    const id = ++nextId;
    pending.set(id, { resolve, reject });
    try {
      bridge.postMessage(JSON.stringify({ ...args, op, id }));
    } catch (error) {
      pending.delete(id);
      reject(error);
    }
  });
}

if (bridge) {
  document.documentElement.dataset.platform = "android";
  bridge.onmessage = ({ data }) => {
    const { id, error, value } = JSON.parse(data);
    const request = pending.get(id);
    if (!request) return;
    pending.delete(id);
    if (error) request.reject(new Error(error));
    else request.resolve(value);
  };

  // These methods are used by the existing UI for platform integration.
  window.electron = {
    getPlatform: async () => "android",
    secureGet: (key) => androidRequest("secureGet", { key }),
    secureSet: (key, value) => androidRequest("secureSet", { key, value }),
    openExternal: (url) => androidRequest("openExternal", { url }),
  };
}
