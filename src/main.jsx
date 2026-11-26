import { isAndroid } from "./android/bridge";
import React from "react";
import ReactDOM from "react-dom/client";
import "./i18n";
import App from "./App";
import "./styles/global.css";
import "./android/android.css";
import { appIcon, appName } from "./utils/branding";

if (isAndroid) {
  document.title = appName;
  const favicon = document.querySelector('link[rel="icon"]');
  favicon.href = appIcon;
  favicon.type = "image/png";
}

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
