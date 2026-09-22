#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p test-results/browser-reader
WORK="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  if [[ -n "$SERVER_PID" ]]; then kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT
python3 - "$WORK" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET
reports = list(pathlib.Path('target/surefire-reports').glob('TEST-*.xml'))
if not reports: raise SystemExit('Run mvn verify before the browser fixture')
root = ET.parse(reports[0]).getroot()
cp = next(p.attrib['value'] for p in root.find('properties') if p.attrib['name'] == 'java.class.path')
pathlib.Path(sys.argv[1], 'classpath').write_text(cp)
PY
CP="$(cat "$WORK/classpath")"
mkdir -p "$WORK/classes"
javac -encoding UTF-8 -cp "$CP" -d "$WORK/classes" scripts/verification/SeedBook.java scripts/verification/ReadingWindowQaServer.java
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$WORK/classes:$CP" ReadingWindowQaServer "$WORK/data" 19872 > test-results/browser-reader/server.log 2>&1 &
SERVER_PID="$!"
python3 - <<'PY'
import socket,time
for i in range(120):
    try:
        with socket.create_connection(('127.0.0.1',19872),timeout=.2): break
    except OSError: time.sleep(.25)
else: raise SystemExit('Isolated QA server failed to start')
PY
python3 scripts/verification/browser_reader_smoke.py
