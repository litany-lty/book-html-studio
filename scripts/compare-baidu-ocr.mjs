#!/usr/bin/env node

import { readFile, mkdir, writeFile, chmod } from "node:fs/promises";
import { resolve, basename } from "node:path";
import { createHash } from "node:crypto";
import { isIP } from "node:net";

const AUTH_ENDPOINT = "https://aip.baidubce.com/oauth/2.0/token";
const PPOCR_ENDPOINT = "https://aip.baidubce.com/rest/2.0/ocr/v1/pp_ocrv5";
const VL_SUBMIT_ENDPOINT = "https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task";
const VL_QUERY_ENDPOINT = `${VL_SUBMIT_ENDPOINT}/query`;
const OUTPUT_DIR = resolve("verification");
const PPOCR_OUTPUT = resolve(OUTPUT_DIR, "comparison-ppocrv5.json");
const VL_OUTPUT = resolve(OUTPUT_DIR, "comparison-paddlevl16.json");
const SUMMARY_OUTPUT = resolve(OUTPUT_DIR, "comparison-summary.json");
const MAX_PNG_BYTES = 10 * 1024 * 1024;
const MAX_API_BYTES = 32 * 1024 * 1024;
const MAX_RESULT_BYTES = 64 * 1024 * 1024;
const POLL_INTERVAL_MS = 5_000;
const POLL_LIMIT_MS = 180_000;

let apiKey = "";
let secretKey = "";
let accessToken = "";
let resumeMode = false;
const startedAt = Date.now();
const summary = {
  comparison: "baidu-ppocrv5-vs-paddleocr-vl-1.6",
  ppocrProtocol: {
    documentation: "PP-OCRv6（官方文档，2026-09-04）",
    documentationUrl: "https://cloud.baidu.com/doc/OCR/s/6mncwkr9c",
    endpointName: "pp_ocrv5",
    actualVersion: "实际模型版本需以上游响应为准，本脚本不推断",
  },
  source: null,
  auth: { http: null, succeeded: false, expiresIn: null },
  ppocrv5: { attempted: false, succeeded: false, http: null, elapsedMs: null, errorCode: null, error: null },
  paddlevl16: { attempted: false, succeeded: false, submitHTTP: null, elapsedMs: null, terminalState: null, errorCode: null, error: null },
  totalElapsedMs: null,
};

function parseEnv(text) {
  const values = {};
  for (const raw of text.split(/\r?\n/u)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const match = line.match(/^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/u);
    if (!match) continue;
    let value = match[2].trim();
    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    else value = value.replace(/\s+#.*$/u, "").trim();
    values[match[1]] = value;
  }
  return values;
}

function redact(value) {
  let text = String(value ?? "");
  for (const secret of [apiKey, secretKey, accessToken]) {
    if (secret) text = text.split(secret).join("[REDACTED]");
  }
  text = text.replace(/([?&](?:access_token|client_id|client_secret)=)[^&\s"']*/giu, "$1[REDACTED]");
  return text.replace(/https?:\/\/[^\s"'<>\\]+/giu, (raw) => {
    const suffix = raw.match(/[),.;，。]+$/u)?.[0] || "";
    const candidate = suffix ? raw.slice(0, -suffix.length) : raw;
    try {
      const url = new URL(candidate);
      url.search = "";
      url.hash = "";
      return `${url.toString()}${suffix}`;
    } catch {
      return `${candidate.replace(/[?#].*$/u, "")}${suffix}`;
    }
  });
}

function safeScalar(value) {
  if (value == null || typeof value === "number" || typeof value === "boolean") return value ?? null;
  return redact(value);
}

function scrub(value, key = "") {
  const normalized = key.toLowerCase();
  if (["parse_result_url", "markdown_url"].includes(normalized)) return "[RESULT_URL_REMOVED]";
  if (/(?:access[_-]?token|api[_-]?key|secret|authorization)/u.test(normalized)) return "[REDACTED]";
  if (typeof value === "string") return redact(value);
  if (Array.isArray(value)) return value.map((item) => scrub(item));
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([childKey, child]) => [redact(childKey).slice(0, 200), scrub(child, childKey)]));
  }
  return value;
}

function safeError(error) {
  if (error?.name === "AbortError") return "请求超时";
  if (error instanceof SyntaxError) return "服务返回无法解析的 JSON";
  if (error?.code === "ENOENT") return "项目 .env 或输入文件不存在";
  if (error instanceof TypeError) return "网络请求失败";
  return redact(error?.message || "诊断失败").replace(/https?:\/\/\S+/giu, "[URL_REDACTED]").slice(0, 300);
}

function logState(model, state, extra = {}) {
  process.stdout.write(`${JSON.stringify({ model, state, elapsedMs: Date.now() - startedAt, ...extra })}\n`);
}

async function readBodyLimited(response, maxBytes) {
  const declared = Number(response.headers.get("content-length") || 0);
  if (Number.isFinite(declared) && declared > maxBytes) throw new Error("响应超过大小限制");
  const reader = response.body?.getReader();
  if (!reader) return "";
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > maxBytes) {
      await reader.cancel();
      throw new Error("响应超过大小限制");
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

function parseJsonOrJsonLines(text) {
  if (!text.trim()) return {};
  try {
    return JSON.parse(text);
  } catch (firstError) {
    const lines = text.split(/\r?\n/u).filter((line) => line.trim());
    if (lines.length < 2) throw firstError;
    return lines.map((line) => JSON.parse(line));
  }
}

async function requestJson(url, options, timeoutMs, maxBytes = MAX_API_BYTES) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...options, redirect: "manual", signal: controller.signal });
    if (response.status >= 300 && response.status < 400) throw new Error("服务返回重定向，已拒绝跟随");
    const text = await readBodyLimited(response, maxBytes);
    return { status: response.status, ok: response.ok, json: parseJsonOrJsonLines(text) };
  } finally {
    clearTimeout(timer);
  }
}

function withToken(endpoint) {
  const url = new URL(endpoint);
  url.searchParams.set("access_token", accessToken);
  return url;
}

function apiErrorCode(json) {
  return safeScalar(json?.error_code ?? json?.errorCode ?? json?.result?.error_code ?? null);
}

function apiErrorMessage(json) {
  return redact(json?.error_msg ?? json?.errorMsg ?? json?.message ?? json?.result?.message ?? "服务返回失败").slice(0, 300);
}

async function writePrivate(path, value) {
  await mkdir(OUTPUT_DIR, { recursive: true });
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await chmod(path, 0o600);
}

async function hasSuccessfulResult(path) {
  try {
    const value = JSON.parse(await readFile(path, "utf8"));
    return value?.succeeded === true || value?.ppocrv5?.succeeded === true || value?.paddlevl16?.succeeded === true;
  } catch (error) {
    if (error?.code === "ENOENT" || error instanceof SyntaxError) return false;
    throw error;
  }
}

async function refuseSuccessfulRerun() {
  for (const path of [PPOCR_OUTPUT, VL_OUTPUT, SUMMARY_OUTPUT]) {
    if (await hasSuccessfulResult(path)) throw new Error(`已有成功结果 ${basename(path)}，拒绝覆盖或重复计费`);
  }
}

function validatePng(bytes) {
  if (bytes.length === 0 || bytes.length > MAX_PNG_BYTES) throw new Error("输入 PNG 必须大于 0 且不超过 10MB");
  const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (bytes.length < 24 || !signature.every((byte, index) => bytes[index] === byte) || bytes.toString("ascii", 12, 16) !== "IHDR") {
    throw new Error("输入文件不是有效 PNG");
  }
  const width = bytes.readUInt32BE(16);
  const height = bytes.readUInt32BE(20);
  if (width <= 0 || height <= 0) throw new Error("PNG 尺寸无效");
  if (Math.max(width, height) > 8_192) throw new Error("PNG 最长边超过 8192 像素限制");
  return { width, height };
}

function validateFormSize(body) {
  const bytes = Buffer.byteLength(body.toString(), "utf8");
  if (bytes > MAX_PNG_BYTES) throw new Error("Base64 编码后的表单超过 10MB 限制");
  return bytes;
}

function validateResultUrl(raw) {
  if (typeof raw !== "string" || !raw) throw new Error("VL 成功响应缺少 parse_result_url");
  const url = new URL(raw);
  const host = url.hostname.toLowerCase();
  if (url.protocol !== "https:" || url.username || url.password || (url.port && url.port !== "443") || isIP(host)) {
    throw new Error("VL 结果地址未通过安全校验");
  }
  if (!(host.endsWith(".bcebos.com") || host.endsWith(".baidubce.com"))) {
    throw new Error("VL 结果地址不属于允许的百度官方域名");
  }
  return url;
}

async function authenticate() {
  const url = new URL(AUTH_ENDPOINT);
  url.searchParams.set("grant_type", "client_credentials");
  url.searchParams.set("client_id", apiKey);
  url.searchParams.set("client_secret", secretKey);
  const response = await requestJson(url, { method: "POST" }, 30_000);
  summary.auth.http = response.status;
  const auth = response.json;
  accessToken = typeof auth?.access_token === "string" ? auth.access_token : "";
  summary.auth.expiresIn = Number.isFinite(Number(auth?.expires_in)) ? Number(auth.expires_in) : null;
  summary.auth.succeeded = response.ok && Boolean(accessToken);
  if (!summary.auth.succeeded) throw new Error(`认证失败，errorCode=${safeScalar(auth?.error_code ?? auth?.error ?? "unknown")}`);
  logState("auth", "success", { http: response.status });
}

async function runPpocr(base64) {
  const began = Date.now();
  summary.ppocrv5.attempted = true;
  logState("ppocrv5", "submitted");
  try {
    const body = new URLSearchParams({
      image: base64,
      useDocOrientationClassify: "false",
      useDocUnwarping: "false",
      useTextlineOrientation: "false",
    });
    validateFormSize(body);
    const response = await requestJson(withToken(PPOCR_ENDPOINT), {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    }, 60_000);
    summary.ppocrv5.http = response.status;
    summary.ppocrv5.errorCode = apiErrorCode(response.json);
    const acceptedErrorCode = summary.ppocrv5.errorCode == null || summary.ppocrv5.errorCode === 0 || summary.ppocrv5.errorCode === "0";
    const hasPageResult = response.json?.page_result != null;
    summary.ppocrv5.succeeded = response.ok && acceptedErrorCode && hasPageResult;
    if (!summary.ppocrv5.succeeded) summary.ppocrv5.error = apiErrorMessage(response.json);
    await writePrivate(PPOCR_OUTPUT, {
      model: "PP-OCR（官方文档标为 v6，接口名 pp_ocrv5，实际版本未回传确认）",
      succeeded: summary.ppocrv5.succeeded,
      http: response.status,
      response: scrub(response.json),
    });
    logState("ppocrv5", summary.ppocrv5.succeeded ? "success" : "failed", { http: response.status, errorCode: summary.ppocrv5.errorCode });
  } catch (error) {
    summary.ppocrv5.error = safeError(error);
    await writePrivate(PPOCR_OUTPUT, { model: "PP-OCR（官方文档标为 v6，接口名 pp_ocrv5，实际版本未回传确认）", succeeded: false, error: summary.ppocrv5.error });
    logState("ppocrv5", "failed", { errorCode: "local_failure" });
  } finally {
    summary.ppocrv5.elapsedMs = Date.now() - began;
  }
}

function vlState(json) {
  return String(json?.result?.status ?? json?.status ?? "").toLowerCase();
}

async function runPaddleVl(base64, resume = null) {
  const began = Date.now();
  summary.paddlevl16.attempted = true;
  let submitJson = resume?.submitJson ?? null;
  let terminalJson = null;
  try {
    let taskId = resume?.taskId ?? null;
    if (resume) {
      logState("paddlevl16", "resumed");
    } else {
      logState("paddlevl16", "submitted");
      const body = new URLSearchParams({
        file_data: base64,
        file_name: "sample-page25.png",
        analysis_chart: "false",
        return_span_boxes: "true",
        angle_adjust: "false",
        unwarp: "false",
      });
      validateFormSize(body);
      const submit = await requestJson(withToken(VL_SUBMIT_ENDPOINT), {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body,
      }, 60_000);
      summary.paddlevl16.submitHTTP = submit.status;
      submitJson = submit.json;
      taskId = submitJson?.result?.task_id ?? submitJson?.task_id;
      if (!submit.ok || typeof taskId !== "string" || !taskId) {
        summary.paddlevl16.errorCode = apiErrorCode(submitJson);
        throw new Error(`VL 提交失败，errorCode=${summary.paddlevl16.errorCode ?? "unknown"}`);
      }
    }

    const pollStarted = Date.now();
    let previousState = null;
    while (Date.now() - pollStarted < POLL_LIMIT_MS) {
      await new Promise((resolvePromise) => setTimeout(resolvePromise, POLL_INTERVAL_MS));
      const remainingMs = POLL_LIMIT_MS - (Date.now() - pollStarted);
      if (remainingMs <= 0) break;
      const query = await requestJson(withToken(VL_QUERY_ENDPOINT), {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ task_id: taskId }),
      }, Math.min(30_000, remainingMs));
      terminalJson = query.json;
      const state = vlState(terminalJson);
      if (state !== previousState) {
        logState("paddlevl16", state || "unknown", { http: query.status });
        previousState = state;
      }
      if (!query.ok) {
        summary.paddlevl16.errorCode = apiErrorCode(terminalJson);
        throw new Error(`VL 查询失败，errorCode=${summary.paddlevl16.errorCode ?? "unknown"}`);
      }
      if (state === "failed") {
        summary.paddlevl16.terminalState = state;
        summary.paddlevl16.errorCode = apiErrorCode(terminalJson);
        throw new Error(`VL 任务失败，errorCode=${summary.paddlevl16.errorCode ?? "unknown"}`);
      }
      if (state === "success" || state === "done") {
        summary.paddlevl16.terminalState = state;
        break;
      }
      if (!new Set(["pending", "processing", "running"]).has(state)) throw new Error("VL 返回未知任务状态");
    }
    if (!new Set(["success", "done"]).has(summary.paddlevl16.terminalState)) throw new Error("VL 轮询超过 180 秒限制");

    const resultUrl = validateResultUrl(terminalJson?.result?.parse_result_url ?? terminalJson?.parse_result_url);
    const downloaded = await requestJson(resultUrl, { method: "GET", headers: {} }, 60_000, MAX_RESULT_BYTES);
    if (!downloaded.ok) throw new Error(`VL 结果下载失败，HTTP ${downloaded.status}`);
    summary.paddlevl16.succeeded = true;
    await writePrivate(VL_OUTPUT, {
      model: "PaddleOCR-VL-1.6",
      succeeded: true,
      submit: scrub(submitJson),
      terminal: scrub(terminalJson),
      result: scrub(downloaded.json),
    });
    logState("paddlevl16", "downloaded", { file: basename(VL_OUTPUT) });
  } catch (error) {
    summary.paddlevl16.error = safeError(error);
    await writePrivate(VL_OUTPUT, {
      model: "PaddleOCR-VL-1.6",
      succeeded: false,
      submit: scrub(submitJson),
      terminal: scrub(terminalJson),
      error: summary.paddlevl16.error,
    });
    logState("paddlevl16", "failed", { errorCode: summary.paddlevl16.errorCode ?? "local_failure" });
  } finally {
    summary.paddlevl16.elapsedMs = Date.now() - began;
  }
}

async function prepareSource(input) {
  const bytes = await readFile(resolve(input));
  const dimensions = validatePng(bytes);
  const source = {
    file: basename(input),
    bytes: bytes.length,
    sha256: createHash("sha256").update(bytes).digest("hex"),
    ...dimensions,
  };
  return { bytes, source };
}

async function loadCredentials() {
  const env = parseEnv(await readFile(resolve(".env"), "utf8"));
  apiKey = env.BAIDU_OCR_API_KEY || "";
  secretKey = env.BAIDU_OCR_SECRET_KEY || "";
  if (!apiKey || !secretKey) throw new Error("缺少 BAIDU_OCR_API_KEY 或 BAIDU_OCR_SECRET_KEY");
}

async function runFresh(input) {
  await refuseSuccessfulRerun();
  const { bytes, source } = await prepareSource(input);
  summary.source = source;
  await loadCredentials();
  await authenticate();
  const base64 = bytes.toString("base64");
  await runPpocr(base64);
  await runPaddleVl(base64);
  if (!summary.ppocrv5.succeeded || !summary.paddlevl16.succeeded) process.exitCode = 1;
}

async function runResume(input) {
  if (await hasSuccessfulResult(VL_OUTPUT)) throw new Error("已有成功的 PaddleOCR-VL 结果，拒绝重复查询或覆盖");
  const [existingSummary, existingVl, prepared] = await Promise.all([
    readFile(SUMMARY_OUTPUT, "utf8").then(JSON.parse),
    readFile(VL_OUTPUT, "utf8").then(JSON.parse),
    prepareSource(input),
  ]);
  if (!existingSummary?.source?.sha256 || existingSummary.source.sha256 !== prepared.source.sha256) {
    throw new Error("恢复模式输入 PNG 的 SHA-256 与原任务不一致");
  }
  const taskId = existingVl?.submit?.result?.task_id ?? existingVl?.submit?.task_id;
  if (typeof taskId !== "string" || !taskId) throw new Error("已保存 VL 提交结果缺少 task_id");
  Object.assign(summary, existingSummary);
  resumeMode = true;
  summary.source = prepared.source;
  summary.auth = { http: null, succeeded: false, expiresIn: null };
  summary.paddlevl16 = {
    ...summary.paddlevl16,
    attempted: true,
    succeeded: false,
    elapsedMs: null,
    terminalState: null,
    errorCode: null,
    error: null,
  };
  await loadCredentials();
  await authenticate();
  await runPaddleVl(null, { taskId, submitJson: existingVl.submit });
  if (!summary.paddlevl16.succeeded) process.exitCode = 1;
}

async function main() {
  if (process.argv[2] === "--resume-vl" && process.argv.length === 4) return runResume(process.argv[3]);
  if (process.argv.length === 3 && process.argv[2]) return runFresh(process.argv[2]);
  throw new Error("用法：node scripts/compare-baidu-ocr.mjs [--resume-vl] <单个PNG文件>");
}

try {
  await main();
} catch (error) {
  const message = safeError(error);
  if (!summary.auth.succeeded && !summary.auth.error) summary.auth.error = message;
  logState("comparison", "failed", { errorCode: message });
  process.exitCode = 1;
} finally {
  accessToken = "";
  summary.totalElapsedMs = Date.now() - startedAt;
  try {
    if (resumeMode || !(await hasSuccessfulResult(SUMMARY_OUTPUT))) await writePrivate(SUMMARY_OUTPUT, scrub(summary));
  } catch (error) {
    process.stderr.write(`${JSON.stringify({ model: "comparison", state: "summary-write-failed", error: safeError(error) })}\n`);
    process.exitCode = 1;
  }
}
