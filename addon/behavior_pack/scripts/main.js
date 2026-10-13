import { world, system } from "@minecraft/server";
import { http, HttpRequest, HttpHeader, HttpRequestMethod } from "@minecraft/server-net";

// A ponte e o Bedrock Dedicated Server devem estar na mesma máquina.
// Se estiverem em máquinas diferentes, troque 127.0.0.1 pelo IP do host da ponte.
const BRIDGE_URL = "http://127.0.0.1:8765";
const TOKEN = "change-this-token";
const POLL_PATH = `/api/poll?token=${encodeURIComponent(TOKEN)}`;
const ACK_PATH = "/api/ack";

let polling = false;
let elapsedSeconds = 0;
let currentRules = new Map();
const timerLastRun = new Map();
const redstoneLastPower = new Map();
const processedCommandIds = new Set();

function log(message) {
  try {
    world.sendMessage(`§b[LinkBlocks]§r ${message}`);
  } catch {
    console.warn("[LinkBlocks] " + message);
  }
}

async function requestJson(method, path, body = undefined) {
  const request = new HttpRequest(`${BRIDGE_URL}${path}`);
  request.method = method;
  request.timeout = 4;
  request.headers = [new HttpHeader("Content-Type", "application/json")];
  if (body !== undefined) request.body = JSON.stringify(body);
  const response = await http.request(request);
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`HTTP ${response.status}`);
  }
  return response.body ? JSON.parse(response.body) : {};
}

function dimensionFor(id) {
  const normalized = String(id || "overworld").toLowerCase();
  if (normalized === "nether" || normalized === "minecraft:nether") return world.getDimension("nether");
  if (normalized === "the_end" || normalized === "end" || normalized === "minecraft:the_end") return world.getDimension("the_end");
  return world.getDimension("overworld");
}

function locationOf(position) {
  return {
    x: Math.floor(Number(position?.x) || 0),
    y: Math.floor(Number(position?.y) || 64),
    z: Math.floor(Number(position?.z) || 0),
  };
}

function updateDoor(block, open) {
  if (!block || !block.typeId.endsWith("_door")) {
    throw new Error("O alvo precisa apontar para um bloco de porta.");
  }
  const states = block.permutation.getAllStates();
  const upper = Boolean(states.upper_block_bit);
  const bottom = upper ? block.below() : block;
  const top = upper ? block : block.above();
  const parts = [bottom, top].filter(Boolean);
  let changed = false;
  for (const part of parts) {
    if (!part.typeId.endsWith("_door")) continue;
    const partStates = part.permutation.getAllStates();
    if (Object.prototype.hasOwnProperty.call(partStates, "open_bit")) {
      part.setPermutation(part.permutation.withState("open_bit", Boolean(open)));
      changed = true;
    }
  }
  if (!changed) throw new Error("Esta porta não expõe o estado open_bit nesta versão.");
}

function toggleRedstoneLamp(block) {
  if (!block) throw new Error("Bloco alvo não encontrado.");
  const id = block.typeId;
  if (id === "minecraft:redstone_lamp") {
    block.setType("minecraft:lit_redstone_lamp");
    return;
  }
  if (id === "minecraft:lit_redstone_lamp") {
    block.setType("minecraft:redstone_lamp");
    return;
  }
  const states = block.permutation.getAllStates();
  if (Object.prototype.hasOwnProperty.call(states, "lit")) {
    block.setPermutation(block.permutation.withState("lit", !Boolean(states.lit)));
    return;
  }
  throw new Error("O alvo precisa ser uma lâmpada de redstone.");
}

function executeAction(rule) {
  const action = rule.action;
  const target = locationOf(rule.target);
  const dimension = dimensionFor(rule.target?.dimension);
  try {
    switch (action) {
      case "open_door":
        updateDoor(dimension.getBlock(target), true);
        log(`Porta aberta pela regra “${rule.name}”.`);
        break;
      case "close_door":
        updateDoor(dimension.getBlock(target), false);
        log(`Porta fechada pela regra “${rule.name}”.`);
        break;
      case "toggle_light":
        toggleRedstoneLamp(dimension.getBlock(target));
        log(`Lâmpada alternada pela regra “${rule.name}”.`);
        break;
      case "command": {
        const command = String(rule.command || "").trim().replace(/^\/+/, "");
        if (!command) throw new Error("Nenhum comando foi configurado.");
        if (command.length > 300) throw new Error("O comando é longo demais.");
        dimension.runCommand(command);
        log(`Comando executado pela regra “${rule.name}”.`);
        break;
      }
      case "message":
        world.sendMessage(String(rule.command || `LinkBlocks: regra ${rule.name} acionada.`).slice(0, 300));
        break;
      case "day":
        dimension.runCommand("time set day");
        log("Horário alterado para dia.");
        break;
      case "night":
        dimension.runCommand("time set night");
        log("Horário alterado para noite.");
        break;
      case "clear_weather":
        dimension.runCommand("weather clear");
        log("Clima limpo.");
        break;
      default:
        throw new Error("Ação desconhecida: " + action);
    }
    return true;
  } catch (error) {
    log(`Falha em “${rule.name}”: ${error?.message || error}`);
    return false;
  }
}

async function acknowledge(ids) {
  if (!ids.length) return;
  await requestJson(HttpRequestMethod.Post, ACK_PATH, { token: TOKEN, ids });
}

async function pollBridge() {
  if (polling) return;
  polling = true;
  try {
    const snapshot = await requestJson(HttpRequestMethod.Get, POLL_PATH);
    if (Array.isArray(snapshot.rules)) {
      const nextRules = new Map();
      for (const rule of snapshot.rules) {
        if (rule && typeof rule.name === "string") nextRules.set(rule.name, rule);
      }
      currentRules = nextRules;
    }

    const processedNow = [];
    for (const item of (Array.isArray(snapshot.commands) ? snapshot.commands : [])) {
      if (!item?.id || processedCommandIds.has(item.id)) continue;
      processedCommandIds.add(item.id);
      const savedRule = currentRules.get(item.name);
      if (!savedRule) {
        log(`Regra “${item.name}” não existe. Salve-a no app e tente novamente.`);
        processedNow.push(item.id);
        continue;
      }
      const merged = {
        ...savedRule,
        ...item,
        name: savedRule.name,
        action: item.action || savedRule.action,
        target: item.target || savedRule.target,
        command: item.command || savedRule.command,
      };
      if (executeAction(merged)) processedNow.push(item.id);
      else processedCommandIds.delete(item.id);
    }
    if (processedNow.length) {
      try {
        await acknowledge(processedNow);
      } catch (error) {
        console.warn("[LinkBlocks] Falha ao confirmar comandos:", error?.message || error);
      }
    }
  } catch (error) {
    console.warn("[LinkBlocks] Ponte indisponível:", error?.message || error);
  } finally {
    polling = false;
  }
}

function checkRules() {
  elapsedSeconds += 1;
  const now = Date.now();
  for (const rule of currentRules.values()) {
    if (rule.trigger === "timer") {
      const intervalSeconds = Math.max(1, Math.min(86400, Number(rule.intervalSeconds) || 10));
      const last = timerLastRun.get(rule.name);
      if (last === undefined) {
        timerLastRun.set(rule.name, now);
      } else if (now - last >= intervalSeconds * 1000) {
        timerLastRun.set(rule.name, now);
        executeAction(rule);
      }
    }

    if (rule.trigger === "redstone" && rule.monitor) {
      try {
        const monitorDimension = dimensionFor(rule.monitor.dimension);
        const position = locationOf(rule.monitor);
        const block = monitorDimension.getBlock(position);
        const power = block?.getRedstonePower();
        const signal = Number(power) > 0;
        const previous = redstoneLastPower.get(rule.name) || false;
        if (signal && !previous) executeAction(rule);
        redstoneLastPower.set(rule.name, signal);
      } catch (error) {
        console.warn(`[LinkBlocks] Não foi possível observar redstone em “${rule.name}”: ${error?.message || error}`);
      }
    }
  }
}

// One second between local trigger checks; external requests happen every two seconds.
system.runInterval(checkRules, 20);
system.runInterval(pollBridge, 40);
system.runTimeout(() => {
  log("Add-On carregado. Verificando ponte local…");
  pollBridge();
}, 40);
