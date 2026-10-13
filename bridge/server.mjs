import http from "node:http";
import { readFileSync, writeFileSync, renameSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";

const ROOT = dirname(fileURLToPath(import.meta.url));
const STATE_FILE = join(ROOT, "state.json");
const HOST = process.env.LINKBLOCKS_HOST || "0.0.0.0";
const PORT = Number(process.env.LINKBLOCKS_PORT || 8765);
const TOKEN = process.env.LINKBLOCKS_TOKEN || "change-this-token";
const MAX_BODY = 128 * 1024;

function loadState() {
  try {
    if (existsSync(STATE_FILE)) {
      const parsed = JSON.parse(readFileSync(STATE_FILE, "utf8"));
      return {
        rules: Array.isArray(parsed.rules) ? parsed.rules : [],
        commands: Array.isArray(parsed.commands) ? parsed.commands : [],
      };
    }
  } catch (error) {
    console.error("Não foi possível ler state.json:", error.message);
  }
  return { rules: [], commands: [] };
}
let state = loadState();

function persist() {
  const tmp = STATE_FILE + ".tmp";
  writeFileSync(tmp, JSON.stringify(state, null, 2), "utf8");
  renameSync(tmp, STATE_FILE);
}
function json(res, code, data) {
  res.writeHead(code, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  });
  res.end(JSON.stringify(data));
}
function authorized(token) {
  return typeof token === "string" && token.length >= 8 && token === TOKEN;
}
async function readJson(req) {
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
    if (Buffer.byteLength(raw) > MAX_BODY) throw new Error("Corpo da requisição muito grande.");
  }
  try {
    return JSON.parse(raw || "{}");
  } catch {
    throw new Error("JSON inválido.");
  }
}
function validPosition(value) {
  return value && Number.isInteger(Number(value.x)) && Number.isInteger(Number(value.y)) &&
    Number.isInteger(Number(value.z)) &&
    Math.abs(Number(value.x)) <= 30000000 && Math.abs(Number(value.z)) <= 30000000 &&
    Number(value.y) >= -2048 && Number(value.y) <= 2048;
}
function cleanRule(input) {
  const allowedTriggers = new Set(["button", "timer", "redstone"]);
  const allowedActions = new Set([
    "open_door", "close_door", "toggle_light", "command", "message", "day", "night", "clear_weather"
  ]);
  if (!input || typeof input.name !== "string" || !/^[\w .-]{1,64}$/.test(input.name.trim())) {
    throw new Error("Nome da regra inválido (1–64 caracteres; letras, números, espaço, ponto, hífen ou sublinhado).");
  }
  if (!allowedTriggers.has(input.trigger)) throw new Error("Gatilho inválido.");
  if (!allowedActions.has(input.action)) throw new Error("Ação inválida.");
  if (!validPosition(input.target)) throw new Error("Coordenadas do alvo inválidas.");
  if (input.trigger === "redstone" && !validPosition(input.monitor)) throw new Error("Coordenadas do bloco monitorado inválidas.");
  const intervalSeconds = Math.max(1, Math.min(86400, Number.parseInt(input.intervalSeconds, 10) || 10));
  const command = typeof input.command === "string" ? input.command.trim().slice(0, 300) : "";
  if (input.action === "command" && !command) throw new Error("Informe o comando de Minecraft.");
  return {
    name: input.name.trim(),
    trigger: input.trigger,
    action: input.action,
    intervalSeconds,
    target: { x: Number(input.target.x), y: Number(input.target.y), z: Number(input.target.z), dimension: input.target.dimension || "overworld" },
    monitor: input.monitor && validPosition(input.monitor)
      ? { x: Number(input.monitor.x), y: Number(input.monitor.y), z: Number(input.monitor.z), dimension: input.monitor.dimension || "overworld" }
      : null,
    command,
    updatedAt: new Date().toISOString(),
  };
}

const server = http.createServer(async (req, res) => {
  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    });
    res.end();
    return;
  }

  const url = new URL(req.url || "/", "http://localhost");
  if (req.method === "GET" && (url.pathname === "/health" || url.pathname === "/api/health")) {
    json(res, 200, { ok: true, service: "minecraft-linkblocks-bridge", port: PORT });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/poll") {
    if (!authorized(url.searchParams.get("token"))) {
      json(res, 401, { ok: false, error: "Chave inválida." });
      return;
    }
    json(res, 200, { ok: true, rules: state.rules, commands: state.commands.slice(0, 25) });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/ingest") {
    try {
      const body = await readJson(req);
      if (!authorized(body.token)) {
        json(res, 401, { ok: false, error: "Chave inválida. Confira o token no app, ponte e add-on." });
        return;
      }
      const endpoint = body.endpoint;
      const payload = body.payload || {};
      if (endpoint === "ping") {
        json(res, 200, { ok: true, message: "Ponte LinkBlocks conectada." });
        return;
      }
      if (endpoint === "rules") {
        const rule = cleanRule(payload);
        state.rules = state.rules.filter((item) => item.name !== rule.name);
        state.rules.push(rule);
        persist();
        json(res, 200, { ok: true, message: "Regra salva na ponte.", name: rule.name });
        return;
      }
      if (endpoint === "command") {
        const name = typeof payload.name === "string" ? payload.name.trim().slice(0, 64) : "";
        if (!name) {
          json(res, 400, { ok: false, error: "O comando precisa indicar o nome da regra/bloco." });
          return;
        }
        const command = {
          id: randomUUID(),
          name,
          action: typeof payload.action === "string" ? payload.action : undefined,
          target: validPosition(payload.target) ? {
            x: Number(payload.target.x), y: Number(payload.target.y), z: Number(payload.target.z),
            dimension: payload.target.dimension || "overworld",
          } : undefined,
          command: typeof payload.command === "string" ? payload.command.trim().slice(0, 300) : undefined,
          createdAt: new Date().toISOString(),
        };
        if (state.commands.length >= 500) {
          json(res, 429, { ok: false, error: "A fila chegou ao limite; aguarde o servidor processar os comandos." });
          return;
        }
        state.commands.push(command);
        persist();
        json(res, 202, { ok: true, message: "Comando colocado na fila do servidor.", id: command.id });
        return;
      }
      json(res, 400, { ok: false, error: "Endpoint inválido." });
    } catch (error) {
      json(res, 400, { ok: false, error: error.message || "Erro na requisição." });
    }
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/ack") {
    try {
      const body = await readJson(req);
      if (!authorized(body.token)) {
        json(res, 401, { ok: false, error: "Chave inválida." });
        return;
      }
      const ids = new Set(Array.isArray(body.ids) ? body.ids.filter((id) => typeof id === "string") : []);
      const before = state.commands.length;
      state.commands = state.commands.filter((item) => !ids.has(item.id));
      if (state.commands.length !== before) persist();
      json(res, 200, { ok: true, acknowledged: before - state.commands.length });
    } catch (error) {
      json(res, 400, { ok: false, error: error.message || "Erro na confirmação." });
    }
    return;
  }

  json(res, 404, { ok: false, error: "Rota não encontrada." });
});

server.listen(PORT, HOST, () => {
  console.log("Minecraft LinkBlocks bridge online");
  console.log(`Local: http://127.0.0.1:${PORT}/health`);
  console.log(`Bind: ${HOST}:${PORT}`);
  console.log("Token:", TOKEN === "change-this-token" ? "PADRÃO — troque a chave antes de usar" : "chave personalizada carregada");
});
server.on("error", (error) => {
  console.error("Falha ao iniciar a ponte:", error.message);
  process.exitCode = 1;
});
