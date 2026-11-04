import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import net from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

const delay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

async function availablePort() {
  const probe = net.createServer();
  await new Promise((resolve, reject) => {
    probe.once("error", reject);
    probe.listen(0, "127.0.0.1", resolve);
  });
  const address = probe.address();
  assert.ok(address && typeof address === "object");
  await new Promise((resolve, reject) => {
    probe.close((error) => error ? reject(error) : resolve());
  });
  return address.port;
}

async function waitForPage(debugPort, pageUrl, browserLogs) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${debugPort}/json/list`, {
        signal: AbortSignal.timeout(Math.min(1000, Math.max(1, deadline - Date.now()))),
      });
      const targets = await response.json();
      const page = targets.find((target) =>
        target.type === "page" && target.url.startsWith(pageUrl));
      if (page?.webSocketDebuggerUrl) return page;
    } catch {
      // The browser may not have opened its debugging endpoint yet.
    }
    await delay(100);
  }
  throw new Error(`browser page did not become debuggable\n${browserLogs()}`);
}

async function connectDebugger(webSocketUrl, { commandTimeoutMs = 5000, connectionTimeoutMs = 5000 } = {}) {
  const socket = new WebSocket(webSocketUrl);
  let commandId = 0;
  const pending = new Map();
  let disconnectedError;
  let openingTimer;
  let rejectOpening;
  const disconnect = (error) => {
    if (disconnectedError) return;
    disconnectedError = error;
    clearTimeout(openingTimer);
    rejectOpening?.(error);
    for (const command of pending.values()) {
      clearTimeout(command.timer);
      command.reject(error);
    }
    pending.clear();
  };
  socket.addEventListener("close", () => disconnect(new Error("DEBUGGER_CONNECTION_CLOSED")), { once: true });
  socket.addEventListener("error", () => disconnect(new Error("DEBUGGER_CONNECTION_ERROR")), { once: true });
  socket.addEventListener("message", (event) => {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch {
      disconnect(new Error("DEBUGGER_INVALID_MESSAGE"));
      socket.close();
      return;
    }
    const command = pending.get(message.id);
    if (!command) return;
    pending.delete(message.id);
    clearTimeout(command.timer);
    if (message.error) command.reject(new Error(message.error.message));
    else command.resolve(message.result);
  });

  await new Promise((resolve, reject) => {
    rejectOpening = reject;
    openingTimer = setTimeout(() => {
      disconnect(new Error("DEBUGGER_CONNECTION_TIMEOUT"));
      socket.close();
    }, connectionTimeoutMs);
    socket.addEventListener("open", () => {
      if (disconnectedError) return;
      clearTimeout(openingTimer);
      rejectOpening = undefined;
      resolve();
    }, { once: true });
  });

  const send = (method, params = {}, timeoutMs = commandTimeoutMs) => new Promise((resolve, reject) => {
    if (disconnectedError || socket.readyState !== 1) {
      reject(disconnectedError ?? new Error("DEBUGGER_CONNECTION_CLOSED"));
      return;
    }
    const id = ++commandId;
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`DEBUGGER_COMMAND_TIMEOUT: ${method}`));
    }, timeoutMs);
    pending.set(id, { resolve, reject, timer });
    try {
      socket.send(JSON.stringify({ id, method, params }));
    } catch (error) {
      clearTimeout(timer);
      pending.delete(id);
      reject(error);
    }
  });

  return { socket, send };
}

async function stopBrowser(process) {
  if (!process || process.exitCode !== null) return;
  const closed = await Promise.race([
    once(process, "exit").then(() => true),
    delay(5000).then(() => false),
  ]);
  if (closed) return;
  process.kill();
  await Promise.race([
    once(process, "exit"),
    delay(5000),
  ]);
}

const browserPath = process.env.AUDIT_VISUAL_TEST_BROWSER;
assert.ok(browserPath, "AUDIT_VISUAL_TEST_BROWSER must point to a Chromium-based browser");
const viewportMatch = /^([0-9]+)x([0-9]+)$/.exec(
  process.env.AUDIT_VISUAL_TEST_VIEWPORT ?? "1440x900",
);
assert.ok(viewportMatch, "AUDIT_VISUAL_TEST_VIEWPORT must use WIDTHxHEIGHT");
const viewportWidth = Number(viewportMatch[1]);
const viewportHeight = Number(viewportMatch[2]);

const frontendRoot = fileURLToPath(new URL("../..", import.meta.url));
const profileDirectory = mkdtempSync(path.join(tmpdir(), "audit-login-visual-"));
const port = 4179;
const debugPort = await availablePort();
const unauthenticatedAuthFixture = {
  name: "login-page-visual-auth-fixture",
  enforce: "pre",
  configureServer(viteServer) {
    viteServer.middlewares.use("/api/v1/auth/me", (_request, response) => {
      response.statusCode = 401;
      response.setHeader("Content-Type", "application/json");
      response.end(JSON.stringify({
        code: 401,
        message: "unauthenticated",
        data: null,
      }));
    });
  },
};
const server = await createServer({
  root: frontendRoot,
  logLevel: "error",
  plugins: [unauthenticatedAuthFixture],
  server: {
    host: "127.0.0.1",
    port,
    strictPort: true,
  },
});

let browser;
let debuggerConnection;
let browserLog = "";

try {
  await server.listen();
  const pageUrl = `http://127.0.0.1:${port}`;
  browser = spawn(browserPath, [
    "--headless=new",
    "--disable-gpu",
    "--disable-background-networking",
    "--disable-component-update",
    "--no-first-run",
    "--no-default-browser-check",
    `--user-data-dir=${profileDirectory}`,
    `--remote-debugging-port=${debugPort}`,
    "--remote-allow-origins=*",
    `--window-size=${viewportWidth},${viewportHeight}`,
    pageUrl,
  ], {
    stdio: ["ignore", "ignore", "pipe"],
  });
  browser.stderr.setEncoding("utf8");
  browser.stderr.on("data", (chunk) => {
    browserLog += chunk;
  });

  const page = await waitForPage(debugPort, pageUrl, () => browserLog);
  debuggerConnection = await connectDebugger(page.webSocketDebuggerUrl);
  await debuggerConnection.send("Emulation.setDeviceMetricsOverride", {
    width: viewportWidth,
    height: viewportHeight,
    deviceScaleFactor: 1,
    mobile: viewportWidth <= 720,
  });

  const deadline = Date.now() + 15000;
  let loginReady = false;
  while (Date.now() < deadline) {
    const evaluation = await debuggerConnection.send("Runtime.evaluate", {
      expression: "document.querySelector('input[autocomplete=username]') !== null",
      returnByValue: true,
    });
    if (evaluation.result.value === true) {
      loginReady = true;
      break;
    }
    await delay(100);
  }
  assert.ok(loginReady, `login page did not render\n${browserLog}`);

  const evaluation = await debuggerConnection.send("Runtime.evaluate", {
    expression: "document.documentElement.outerHTML",
    returnByValue: true,
  });
  const dom = evaluation.result.value;

  assert.match(dom, /<main[^>]*aria-label="智能审计系统登录"/);
  assert.match(dom, /<aside[^>]*aria-label="系统品牌区"/);
  assert.match(dom, /data-brand-logo-slot="true"[^>]*> *<[/]div>/);
  assert.match(dom, />智能审计系统</);
  assert.match(dom, />Intelligent Audit System</);
  assert.match(dom, />用户登录</);
  assert.match(dom, /placeholder="请输入用户名"/);
  assert.match(dom, /placeholder="请输入密码"/);
  assert.match(dom, />安全认证 · 会话保护</);

  const viewport = await debuggerConnection.send("Runtime.evaluate", {
    expression: "({ clientWidth: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth })",
    returnByValue: true,
  });
  assert.ok(
    viewport.result.value.scrollWidth <= viewport.result.value.clientWidth,
    `page overflows horizontally at ${viewportWidth}x${viewportHeight}`,
  );

  const screenshotPath = process.env.AUDIT_VISUAL_TEST_SCREENSHOT;
  if (screenshotPath) {
    const screenshot = await debuggerConnection.send("Page.captureScreenshot", {
      format: "png",
      fromSurface: true,
      captureBeyondViewport: false,
    });
    writeFileSync(screenshotPath, Buffer.from(screenshot.data, "base64"));
  }
} finally {
  if (debuggerConnection) {
    try {
      await debuggerConnection.send("Browser.close", {}, 1000);
    } catch {
      // Closing the browser can end the debugging connection before its response arrives.
    }
    debuggerConnection.socket.close();
  }
  await stopBrowser(browser);
  await server.close();
  rmSync(profileDirectory, {
    recursive: true,
    force: true,
    maxRetries: 10,
    retryDelay: 100,
  });
}
