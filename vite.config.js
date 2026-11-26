import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const isDist = process.env.ELECTRON_DIST === "1";
  const isAndroid = process.env.STREAMBERT_ANDROID === "1";
  return {
    plugins: [react()],
    base: "./",
    define: { "import.meta.env.VITE_STREAMBERT_ANDROID": JSON.stringify(isAndroid ? "1" : "0") },
    build: {
      minify: "terser",
      terserOptions: {
        compress: {
          drop_console: isDist,
          drop_debugger: isDist,
        },
      },
      rollupOptions: {
        output: {
          manualChunks: {
            react: ["react", "react-dom"],
            settings: ["./src/pages/SettingsPage"],
            movie: ["./src/pages/MoviePage"],
            tv: ["./src/pages/TVPage"],
            downloads: ["./src/pages/DownloadsPage"],
          },
        },
      },
    },
  };
});
