import { createRoot } from "react-dom/client";
import App from "./app/App.tsx";
import { AuthGate, AuthProvider } from "./auth/AuthProvider.tsx";
import "./styles/index.css";

createRoot(document.getElementById("root")!).render(
  <AuthProvider>
    <AuthGate>
      <App />
    </AuthGate>
  </AuthProvider>,
);
