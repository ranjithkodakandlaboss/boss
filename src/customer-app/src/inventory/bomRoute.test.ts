// @ts-expect-error vitest runs on Node, where this module exists
import { readFileSync } from "node:fs";
import { expect, it } from "vitest";

it("keeps the assets screen from colliding with hashed bundles", () => {
  const nginx = readFileSync("nginx.conf.template", "utf8");
  const vite = readFileSync("vite.config.ts", "utf8");
  expect(vite).toContain('assetsDir: "static"');
  expect(nginx).toContain("location /static/");
  expect(nginx).toContain("try_files $uri /index.html");
  expect(nginx).not.toContain("$uri/");
});
