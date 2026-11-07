import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createReadingWindow } from '../../src/main/resources/static/reading-window.js';

for (const transport of ['accepted', 'rejected', 'throws']) {
  test(`stop beacon ${transport} reports delivery honestly and falls back when needed`, async () => {
    const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
    const statuses = [], errors = [], stops = [];
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {
      connection: { saveData: true }, sendBeacon: () => {
        if (transport === 'throws') throw new TypeError('beacon unavailable');
        return transport === 'accepted';
      }
    } });
    try {
      const controller = createReadingWindow({
        state: { book: { id: 'a', totalPages: 10 }, currentPage: 1, pageCache: new Map() },
        api: { readingWindow: async (_id, body) => ({ ...body, enabled: true, pages: [] }),
          stopReadingWindow: async (id, body) => stops.push({ id, body }) },
        onStatus: snapshot => statuses.push(snapshot?.status), onPageReady() {}, onError: error => errors.push(error)
      });
      await controller.enable({ provider: 'fixture' });
      await controller.stop({ beacon: true });
      assert.equal(controller.active(), false);
      assert.equal(stops.length, transport === 'accepted' ? 0 : 1);
      assert.equal(statuses.at(-1), transport === 'accepted' ? 'STOP_REQUESTED' : 'STOPPED');
      assert.deepEqual(errors, []);
    } finally {
      if (descriptor) Object.defineProperty(globalThis, 'navigator', descriptor);
      else delete globalThis.navigator;
    }
  });
}
