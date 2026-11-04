import { createRoot } from "react-dom/client";
import App from "./app/App.tsx";
import { AuthGate, AuthProvider } from "./auth/AuthProvider.tsx";
import "./styles/index.css";
import { ApplicationBootstrap } from './browser/ApplicationBootstrap';

createRoot(document.getElementById("root")!).render(
  <ApplicationBootstrap><AuthProvider>
    <AuthGate>
      <App />
    </AuthGate>
  </AuthProvider></ApplicationBootstrap>,
);
