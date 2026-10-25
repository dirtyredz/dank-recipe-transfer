import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

export function verifyGameTest(log) {
  const counts = [...log.matchAll(/\bAll (\d+) required tests passed\b/g)];
  if (!counts.length || Number(counts.at(-1)[1]) <= 0) throw Error('No positive GameTest completion count');
  if (/\b[1-9]\d* required tests failed\b/.test(log)) throw Error('Required GameTests failed');
  return { tests: Number(counts.at(-1)[1]), requiredFailures: 0 };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { console.log(JSON.stringify(verifyGameTest(fs.readFileSync(process.argv[2], 'utf8')))); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
