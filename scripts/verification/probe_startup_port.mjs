import assert from 'node:assert/strict';
import {copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';

function run(port) {
  const dir = mkdtempSync(join(tmpdir(), 'book-startup-port-'));
  try {
    mkdirSync(join(dir, 'jdk/bin'), {recursive: true});
    mkdirSync(join(dir, 'bin'));
    copyFileSync(new URL('../../run.sh', import.meta.url), join(dir, 'run.sh'));
    writeFileSync(join(dir, 'jdk/bin/java'), '#!/bin/sh\nprintf \'openjdk version "17.0.1"\\n\' >&2\n', {mode: 0o755});
    // Stop at the build boundary: this fixture never starts or stops a real service.
    writeFileSync(join(dir, 'bin/mvn'), '#!/bin/sh\nprintf "BUILD_BOUNDARY_REACHED\\n"\nexit 9\n', {mode: 0o755});
    return spawnSync('bash', [join(dir, 'run.sh')], {
      env: {...process.env, JAVA_HOME: join(dir, 'jdk'), PATH: join(dir, 'bin') + ':' + process.env.PATH, PORT: port},
      encoding: 'utf8', timeout: 5000
    });
  } finally {
    rmSync(dir, {recursive: true, force: true});
  }
}

test('invalid and overflowing ports stop before the build', () => {
  for (const port of ['0', '65536', 'not-a-port', '18446744073709551617']) {
    const result = run(port);
    assert.equal(result.status, 2, `${port}: ${result.stdout} ${result.stderr}`);
    assert.doesNotMatch(result.stdout, /BUILD_BOUNDARY_REACHED/);
  }
});

test('decimal ports with leading zeroes do not trigger octal arithmetic', () => {
  for (const port of ['08', '00018765', '65535']) {
    const result = run(port);
    assert.match(result.stdout, /BUILD_BOUNDARY_REACHED/);
    assert.doesNotMatch(result.stderr, /value too great for base|syntax error|invalid arithmetic/);
  }
});
