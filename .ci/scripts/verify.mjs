import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { modProfile } from './build-profile.mjs';

// Shared hosted gate; the mod's declared datagen flag controls the extra task.
const run = (command, args) => execFileSync(command, args, {stdio:'inherit'});
const profile = modProfile();
console.log(`Build profile ${profile.name}: Java ${profile.java}, final archive ${profile.archiveTask}, releases from ${profile.releaseBranch}`);
fs.chmodSync('gradlew', 0o755);
run('./gradlew', ['test', 'build', 'verifyReleaseJar']);
if (JSON.parse(fs.readFileSync('.ci/mod.json', 'utf8')).datagen) {
  run('./gradlew', ['runData']);
  const changes = execFileSync('git', ['status', '--porcelain', '--untracked-files=all', '--', 'src/generated'], {encoding:'utf8'});
  if (changes.trim()) throw Error('Generated resources differ; regenerate and commit them.\n' + changes);
}
