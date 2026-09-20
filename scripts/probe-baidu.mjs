#!/usr/bin/env node

import { readFile, mkdir, writeFile, chmod } from "node:fs/promises";
import { resolve } from "node:path";

const AUTH_URL = "https://aip.baidubce.com/oauth/2.0/token";
const OCR_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/pp_ocrv5";
const FIXED_IMAGE_URL = "https://baidu-ai.bj.bcebos.com/ocr/general.png";
const TIMEOUT_MS = 30_000;
const MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
const OUTPUT = resolve("verification/probe-baidu-ppocr.json");

const summary = {
  probe: "baidu-ppocr-v5",
  authHTTP: null,
  authSucceeded: false,
  expiresIn: null,
  ocrHTTP: null,
  error_code: null,
  error_msg: null,
  responseKeys: [],
  recognizedLineCount: 0,
  samples: [],
};

let apiKey = "";
let secretKey = "";
let accessToken = "";

function parseEnv(text) {
  const result = {};
  for (const rawLine of text.split(/\r?\n/u)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const match = line.match(/^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/u);
    if (!match) continue;
    let value = match[2].trim();
    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    } else {
      value = value.replace(/\s+#.*$/u, "").trim();
    }
    result[match[1]] = value;
  }
  return result;
}

function redact(value) {
  let text = String(value ?? "");
  for (const secret of [apiKey, secretKey, accessToken]) {
    if (secret) text = text.split(secret).join("[REDACTED]");
  }
  return text
    .replace(/([?&](?:access_token|client_id|client_secret)=)[^&\s]*/giu, "$1[REDACTED]")
    .replace(/https?:\/\/\S+/giu, "[URL_REDACTED]")
    .slice(0, 300);
}

function safeFailure(error) {
  if (error?.name === "AbortError") return "请求超时（30 秒）";
  if (error instanceof SyntaxError) return "服务返回的 JSON 无法解析";
  if (error?.code === "ENOENT") return "项目 .env 不存在";
  if (error instanceof TypeError) return "网络请求失败";
  return redact(error?.message || "诊断失败");
}

async function requestJson(url, options) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(url, { ...options, redirect: "manual", signal: controller.signal });
    const json = await readJsonLimited(response);
    return { status: response.status, ok: response.ok, json };
  } finally {
    clearTimeout(timer);
  }
}

async function readJsonLimited(response) {
  const declared = Number(response.headers.get("content-length") || 0);
  if (Number.isFinite(declared) && declared > MAX_RESPONSE_BYTES) {
    throw new Error("服务响应超过 10MB 安全限制");
  }
  const reader = response.body?.getReader();
  if (!reader) return {};
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_RESPONSE_BYTES) {
      await reader.cancel();
      throw new Error("服务响应超过 10MB 安全限制");
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return JSON.parse(new TextDecoder().decode(bytes));
}

function collectWords(node, output = []) {
  if (!node || typeof node !== "object") return output;
  if (Array.isArray(node)) {
    for (const item of node) collectWords(item, output);
    return output;
  }
  for (const key of ["words", "text", "block_content", "rec_text"]) {
    if (typeof node[key] === "string" && node[key].trim()) {
      output.push(node[key].trim());
      break;
    }
  }
  if (Array.isArray(node.rec_texts)) {
    for (const value of node.rec_texts) {
      if (typeof value === "string" && value.trim()) output.push(value.trim());
    }
  }
  for (const [key, value] of Object.entries(node)) {
    if (!["words", "text", "block_content", "rec_text", "rec_texts"].includes(key)) {
      collectWords(value, output);
    }
  }
  return output;
}

function safeScalar(value) {
  if (value == null || typeof value === "number" || typeof value === "boolean") return value ?? null;
  return redact(value);
}

async function saveSummary() {
  await mkdir(resolve("verification"), { recursive: true });
  await writeFile(OUTPUT, `${JSON.stringify(summary, null, 2)}\n`, { mode: 0o600 });
  await chmod(OUTPUT, 0o600);
}

async function main() {
  try {
    const env = parseEnv(await readFile(resolve(".env"), "utf8"));
    apiKey = env.BAIDU_OCR_API_KEY || "";
    secretKey = env.BAIDU_OCR_SECRET_KEY || "";
    if (!apiKey || !secretKey) throw new Error("缺少 BAIDU_OCR_API_KEY 或 BAIDU_OCR_SECRET_KEY");

    const authUrl = new URL(AUTH_URL);
    authUrl.searchParams.set("grant_type", "client_credentials");
    authUrl.searchParams.set("client_id", apiKey);
    authUrl.searchParams.set("client_secret", secretKey);
    const authResponse = await requestJson(authUrl, { method: "POST" });
    summary.authHTTP = authResponse.status;
    const auth = authResponse.json;
    accessToken = typeof auth.access_token === "string" ? auth.access_token : "";
    summary.expiresIn = Number.isFinite(Number(auth.expires_in)) ? Number(auth.expires_in) : null;
    summary.authSucceeded = authResponse.ok && Boolean(accessToken);
    if (!summary.authSucceeded) {
      summary.error_code = safeScalar(auth.error_code ?? auth.error ?? null);
      summary.error_msg = redact(auth.error_description ?? auth.error_msg ?? "认证失败");
      throw new Error("百度智能云认证失败");
    }

    const ocrUrl = new URL(OCR_URL);
    ocrUrl.searchParams.set("access_token", accessToken);
    const body = new URLSearchParams({
      url: FIXED_IMAGE_URL,
      useDocOrientationClassify: "false",
      useDocUnwarping: "false",
      useTextlineOrientation: "false",
    });
    const ocrResponse = await requestJson(ocrUrl, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    summary.ocrHTTP = ocrResponse.status;
    const ocr = ocrResponse.json;
    summary.responseKeys = Object.keys(ocr).sort().map((key) => redact(key).slice(0, 120));
    summary.error_code = safeScalar(ocr.error_code ?? null);
    summary.error_msg = ocr.error_msg == null ? null : redact(ocr.error_msg);
    const words = collectWords(ocr);
    summary.recognizedLineCount = words.length;
    summary.samples = words.slice(0, 3).map((value) => redact(value).slice(0, 80));
    if (!ocrResponse.ok || summary.error_code != null) process.exitCode = 1;
  } catch (error) {
    if (!summary.error_msg) summary.error_msg = safeFailure(error);
    process.exitCode = 1;
  } finally {
    accessToken = "";
    await saveSummary();
    process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
  }
}

await main();
