#!/usr/bin/env bash
set -euo pipefail

BOOK_APP_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
BOOK_JAR="$BOOK_APP_ROOT/target/book-html-studio.jar"
BOOK_RUNTIME_DIR="$BOOK_APP_ROOT/.run-runtime"
BOOK_LOCK_DIR="$BOOK_APP_ROOT/.run.lock"
BOOK_LOCK_HELD=0
BOOK_CHILD_PID=""
cd "$BOOK_APP_ROOT"

if [[ -f .env ]]; then
  set -a
  # .env 仅在当前进程加载；脚本不会打印其中的内容。
  source .env
  set +a
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  BOOK_JAVA_HOME="$JAVA_HOME"
elif [[ -x /usr/libexec/java_home ]]; then
  BOOK_JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
else
  BOOK_JAVA_HOME=""
fi
if [[ -z "$BOOK_JAVA_HOME" && -d /Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ]]; then
  BOOK_JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
fi
if [[ -z "$BOOK_JAVA_HOME" || ! -x "$BOOK_JAVA_HOME/bin/java" ]]; then
  printf '%s\n' '未找到 JDK 17，请先设置 JAVA_HOME。' >&2
  exit 1
fi
BOOK_JAVA_VERSION="$("$BOOK_JAVA_HOME/bin/java" -version 2>&1 | awk -F '"' 'NR==1 { print $2 }')"
BOOK_JAVA_MAJOR="${BOOK_JAVA_VERSION%%.*}"
if [[ ! "$BOOK_JAVA_MAJOR" =~ ^[0-9]+$ ]] || ((BOOK_JAVA_MAJOR < 17)); then
  printf '%s\n' "JAVA_HOME 必须指向 JDK 17 或更高版本，当前版本：${BOOK_JAVA_VERSION:-未知}" >&2
  exit 1
fi
export JAVA_HOME="$BOOK_JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

BOOK_PORT="${PORT:-18765}"
BOOK_APP_ARGS=()
while (($#)); do
  case "$1" in
    --build) shift ;;
    --server.port=*) BOOK_PORT="${1#--server.port=}"; shift ;;
    --server.port)
      if (($# < 2)); then printf '%s\n' '--server.port 缺少端口值。' >&2; exit 2; fi
      BOOK_PORT="$2"; shift 2
      ;;
    *) BOOK_APP_ARGS+=("$1"); shift ;;
  esac
done
if [[ ! "$BOOK_PORT" =~ ^[0-9]+$ ]] || ((BOOK_PORT < 1 || BOOK_PORT > 65535)); then
  printf '%s\n' "端口无效：${BOOK_PORT}" >&2
  exit 2
fi
# Bash 3.2 在 nounset 下不能展开空数组；显式端口也让检查端口与实际监听保持一致。
BOOK_APP_ARGS+=("--server.port=$BOOK_PORT")

release_lock() {
  if ((BOOK_LOCK_HELD)); then
    local owner=""
    if [[ -f "$BOOK_LOCK_DIR/pid" ]]; then owner="$(sed -n '1p' "$BOOK_LOCK_DIR/pid" 2>/dev/null || true)"; fi
    if [[ "$owner" == "$$" ]]; then
      rm -f "$BOOK_LOCK_DIR/pid"
      rmdir "$BOOK_LOCK_DIR" 2>/dev/null || true
    fi
    BOOK_LOCK_HELD=0
  fi
}
cleanup() {
  release_lock
  if [[ -n "$BOOK_CHILD_PID" ]] && kill -0 "$BOOK_CHILD_PID" 2>/dev/null; then
    kill -TERM "$BOOK_CHILD_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

acquire_lock() {
  if mkdir "$BOOK_LOCK_DIR" 2>/dev/null; then
    printf '%s\n' "$$" > "$BOOK_LOCK_DIR/pid"
    BOOK_LOCK_HELD=1
    return
  fi
  local owner=""
  if [[ -f "$BOOK_LOCK_DIR/pid" ]]; then owner="$(sed -n '1p' "$BOOK_LOCK_DIR/pid" 2>/dev/null || true)"; fi
  if [[ ! "$owner" =~ ^[0-9]+$ ]]; then
    printf '%s\n' '启动锁尚未登记有效 owner，已拒绝并发启动，请稍后重试。' >&2
    exit 1
  fi
  if kill -0 "$owner" 2>/dev/null; then
    printf '%s\n' "已有启动流程正在运行（PID ${owner}），请稍后重试。" >&2
    exit 1
  fi
  if ! mkdir "$BOOK_LOCK_DIR/recover" 2>/dev/null; then
    printf '%s\n' '另一个启动流程正在恢复启动锁，请稍后重试。' >&2
    exit 1
  fi
  local confirmed=""
  if [[ -f "$BOOK_LOCK_DIR/pid" ]]; then confirmed="$(sed -n '1p' "$BOOK_LOCK_DIR/pid" 2>/dev/null || true)"; fi
  if [[ "$confirmed" != "$owner" ]] || kill -0 "$owner" 2>/dev/null; then
    rmdir "$BOOK_LOCK_DIR/recover" 2>/dev/null || true
    printf '%s\n' '启动锁状态已变化，已拒绝并发启动。' >&2
    exit 1
  fi
  rm -f "$BOOK_LOCK_DIR/pid" 2>/dev/null || true
  rmdir "$BOOK_LOCK_DIR/recover" 2>/dev/null || true
  if ! rmdir "$BOOK_LOCK_DIR" 2>/dev/null || ! mkdir "$BOOK_LOCK_DIR" 2>/dev/null; then
    printf '%s\n' '启动锁无法恢复，请确认没有其他启动流程。' >&2
    exit 1
  fi
  printf '%s\n' "$$" > "$BOOK_LOCK_DIR/pid"
  BOOK_LOCK_HELD=1
}

listening_pids() {
  lsof -nP -iTCP:"$BOOK_PORT" -sTCP:LISTEN -t 2>/dev/null | sort -u || true
}
is_this_app_process() {
  local pid="$1" process_cwd process_command process_name
  process_cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1)"
  process_command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  process_name="$(ps -p "$pid" -o comm= 2>/dev/null || true)"
  case "$process_name" in java|*/java) ;; *) return 1 ;; esac
  [[ "$process_cwd" == "$BOOK_APP_ROOT" ]] || return 1
  case " $process_command " in
    *" -jar target/book-html-studio.jar "*|*" -jar $BOOK_JAR "*) return 0 ;;
    *" -jar $BOOK_RUNTIME_DIR/book-html-studio-"*".jar "*) return 0 ;;
    *) return 1 ;;
  esac
}
wait_for_exit() {
  local pid="$1" attempt
  for attempt in $(seq 1 75); do
    if ! kill -0 "$pid" 2>/dev/null; then return 0; fi
    sleep .2
  done
  return 1
}

acquire_lock
printf '%s\n' "正在使用 JDK ${BOOK_JAVA_VERSION} 重新构建…"
if ! mvn -q -DskipTests package; then
  printf '%s\n' '构建失败，现有服务未停止。' >&2
  exit 1
fi
if [[ ! -f "$BOOK_JAR" ]]; then
  printf '%s\n' '构建未生成 target/book-html-studio.jar，现有服务未停止。' >&2
  exit 1
fi

# Never run from Maven's mutable output: a later package can corrupt lazy class
# loading or graceful shutdown of the old process before it is replaced.
BOOK_JAR_HASH="$(shasum -a 256 "$BOOK_JAR" | awk '{print $1}')"
if [[ ! "$BOOK_JAR_HASH" =~ ^[a-f0-9]{64}$ ]]; then
  printf '%s\n' '无法核实构建包身份，现有服务未停止。' >&2
  exit 1
fi
mkdir -p "$BOOK_RUNTIME_DIR"
BOOK_RUNTIME_JAR="$BOOK_RUNTIME_DIR/book-html-studio-$BOOK_JAR_HASH.jar"
if [[ ! -f "$BOOK_RUNTIME_JAR" ]]; then
  BOOK_RUNTIME_TEMP="$(mktemp "$BOOK_RUNTIME_DIR/.prepare.XXXXXX")"
  cp "$BOOK_JAR" "$BOOK_RUNTIME_TEMP"
  if [[ "$(shasum -a 256 "$BOOK_RUNTIME_TEMP" | awk '{print $1}')" != "$BOOK_JAR_HASH" ]]; then
    printf '%s\n' '构建包在复制期间发生变化，现有服务未停止。' >&2
    exit 1
  fi
  chmod 400 "$BOOK_RUNTIME_TEMP"
  mv "$BOOK_RUNTIME_TEMP" "$BOOK_RUNTIME_JAR"
fi
if [[ "$(shasum -a 256 "$BOOK_RUNTIME_JAR" | awk '{print $1}')" != "$BOOK_JAR_HASH" ]]; then
  printf '%s\n' '运行副本校验失败，现有服务未停止。' >&2
  exit 1
fi

BOOK_PIDS=( $(listening_pids) )
if ((${#BOOK_PIDS[@]} > 1)); then
  printf '%s\n' "端口 ${BOOK_PORT} 存在多个监听进程，已拒绝停止或启动。" >&2
  exit 1
fi
if ((${#BOOK_PIDS[@]} == 1)); then
  BOOK_OLD_PID="${BOOK_PIDS[0]}"
  if ! is_this_app_process "$BOOK_OLD_PID"; then
    printf '%s\n' "端口 ${BOOK_PORT} 已被其他进程占用，未执行停止操作。" >&2
    exit 1
  fi
  printf '%s\n' "正在停止本项目旧服务（PID ${BOOK_OLD_PID}）…"
  kill -TERM "$BOOK_OLD_PID"
  if ! wait_for_exit "$BOOK_OLD_PID"; then
    printf '%s\n' '旧服务未在 15 秒内退出；未强制终止，也未启动新服务。' >&2
    exit 1
  fi
fi
if [[ -n "$(listening_pids)" ]]; then
  printf '%s\n' "端口 ${BOOK_PORT} 尚未释放，未启动新服务。" >&2
  exit 1
fi

printf '%s\n' "纸页工坊：http://127.0.0.1:${BOOK_PORT}（Ctrl+C 停止）"
# 资源预算（阶段2，均为暂定参数）：BOOK_XMX 为 JVM 堆上限（默认 768m，运行中不可任意扩大）；
# RENDER_MAX_CONCURRENT / RENDER_MAX_IN_FLIGHT_MB / RENDER_MAX_WAIT_MS 控制共享渲染准入；
# RENDER_WORKER_TIMEOUT_S 控制独立解码进程超时。
BOOK_XMX="${BOOK_XMX:-768m}"
"$JAVA_HOME/bin/java" -Xmx"$BOOK_XMX" -jar "$BOOK_RUNTIME_JAR" "${BOOK_APP_ARGS[@]}" &
BOOK_CHILD_PID=$!
BOOK_STARTED=0
for BOOK_ATTEMPT in $(seq 1 150); do
  if ! kill -0 "$BOOK_CHILD_PID" 2>/dev/null; then break; fi
  BOOK_PIDS=( $(listening_pids) )
  if ((${#BOOK_PIDS[@]} == 1)) && [[ "${BOOK_PIDS[0]}" == "$BOOK_CHILD_PID" ]]; then
    BOOK_STARTED=1
    break
  fi
  sleep .2
done
if ((BOOK_STARTED == 0)); then
  printf '%s\n' "服务未能在端口 ${BOOK_PORT} 启动。" >&2
  if kill -0 "$BOOK_CHILD_PID" 2>/dev/null; then kill -TERM "$BOOK_CHILD_PID" 2>/dev/null || true; fi
  wait "$BOOK_CHILD_PID" 2>/dev/null || true
  BOOK_CHILD_PID=""
  exit 1
fi

release_lock
stop_child() {
  if [[ -n "$BOOK_CHILD_PID" ]] && kill -0 "$BOOK_CHILD_PID" 2>/dev/null; then
    kill -TERM "$BOOK_CHILD_PID" 2>/dev/null || true
  fi
}
trap stop_child INT TERM
set +e
wait "$BOOK_CHILD_PID"
BOOK_STATUS=$?
if kill -0 "$BOOK_CHILD_PID" 2>/dev/null; then
  wait_for_exit "$BOOK_CHILD_PID" || printf '%s\n' '服务收到停止信号后仍未退出，未强制终止。' >&2
fi
BOOK_CHILD_PID=""
exit "$BOOK_STATUS"
