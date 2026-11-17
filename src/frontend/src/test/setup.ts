import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

if (!(Map as unknown as { groupBy?: unknown }).groupBy) {
  (Map as unknown as { groupBy: (items: unknown[], keyFn: (item: unknown) => unknown) => Map<unknown, unknown[]> }).groupBy = (items, keyFn) => {
    const map = new Map();
    for (const item of items) {
      const key = keyFn(item);
      const list = map.get(key) ?? [];
      list.push(item);
      map.set(key, list);
    }
    return map;
  };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
