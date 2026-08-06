#!/usr/bin/env node
/**
 * Serves the selector-ambiguity fixture on 127.0.0.1 so the iframe is same-origin.
 * (file:// would give the iframe an opaque origin and frameLocator would not resolve,
 * which would make the cross-frame ambiguity case untestable rather than tested.)
 *
 *   node tools/fixtures/ambiguous-selectors/serve.mjs [--port 8731]
 *
 * Node built-ins only. Binds to loopback only; serves exactly the two fixture files.
 */
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const PORT = Number(
  (process.argv.find((a) => a.startsWith('--port=')) || '').split('=')[1] ||
    process.argv[process.argv.indexOf('--port') + 1] ||
    8731,
);

const FILES = {
  '/': 'index.html',
  '/index.html': 'index.html',
  '/frame.html': 'frame.html',
};

const server = createServer((req, res) => {
  const path = (req.url || '/').split('?')[0];
  const file = FILES[path];
  if (!file) {
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('not a fixture file');
    return;
  }
  res.writeHead(200, {
    'content-type': 'text/html; charset=utf-8',
    'cache-control': 'no-store',
  });
  res.end(readFileSync(join(HERE, file)));
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`ambiguity fixture on http://127.0.0.1:${PORT}/`);
});
