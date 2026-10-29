import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Single resolver for loader/Minecraft/Java profiles shared by CI, Gradle and sync tooling.
// A mod selects one with .ci/mod.json "profile"; absent means the legacy Forge default.
const here = path.dirname(fileURLToPath(import.meta.url));
export const profilesFile = path.join(here, '..', 'build-profiles.json');
const requireThat = (condition, message) => { if (!condition) throw Error(message); };
const propertyValue = (text, key) => new RegExp(`^\\s*${key}\\s*=\\s*(\\S+)\\s*$`, 'm').exec(text)?.[1];
// Git-style branch names: no empty, '.' or '..' segments.
const branchPattern = /^(?!(?:.*\/)?\.{1,2}(?:\/|$))[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)*$/;

export function loadProfiles(file = profilesFile) {
  const table = JSON.parse(fs.readFileSync(file, 'utf8'));
  requireThat(table && typeof table.profiles === 'object' && table.profiles[table.default], 'Invalid build profile table');
  for (const [name, p] of Object.entries(table.profiles)) {
    requireThat(name === `${p.loader}-${p.minecraft}`, 'Profile name must be <loader>-<minecraft>: ' + name);
    requireThat(['forge', 'neoforge'].includes(p.loader) && /^\d+\.\d+(?:\.\d+)?$/.test(p.minecraft)
      && Number.isInteger(p.java) && p.java >= 17, 'Invalid loader, Minecraft or Java in profile ' + name);
    requireThat(['legacy', 'qualified'].includes(p.identity) && typeof p.repositoryLatest === 'boolean'
      && typeof p.metadata === 'string' && ['jar', 'reobfJar'].includes(p.archiveTask)
      && (p.loader === 'forge') === (p.archiveTask === 'reobfJar'), 'Inconsistent archive or identity in profile ' + name);
    requireThat(Array.isArray(p.forbiddenMetadata) && Array.isArray(p.supersededDatapackFiles)
      && typeof p.testDatapack === 'string' && Array.isArray(p.modDataPacks) && p.modDataPacks.length
      && p.modDataPacks.every(pack => typeof pack === 'string' && pack), 'Invalid datapack/metadata in profile ' + name);
  }
  return table;
}

/** The profile a mod declares, with its release branch. Unknown or malformed declarations fail. */
export function resolveProfile(config, table = loadProfiles()) {
  requireThat(config && typeof config === 'object', 'Invalid .ci/mod.json');
  const name = config.profile ?? table.default;
  requireThat(typeof name === 'string' && Object.hasOwn(table.profiles, name), 'Unknown build profile: ' + name);
  const releaseBranch = config.releaseBranch ?? 'main';
  requireThat(typeof releaseBranch === 'string' && branchPattern.test(releaseBranch), 'Invalid releaseBranch');
  requireThat(name === table.default || releaseBranch !== 'main' || table.profiles[name].repositoryLatest,
    'A non-default profile must declare its own releaseBranch');
  return { name, ...table.profiles[name], releaseBranch };
}

/** Release tag and jar name. Legacy keeps the historical Forge v<version> line byte-for-byte. */
export function releaseIdentity(profile, modId, version) {
  if (profile.identity === 'legacy') return { tag: 'v' + version, jarFilename: `${modId}-${profile.minecraft}-${version}.jar` };
  return { tag: `${profile.loader}-${profile.minecraft}-v${version}`,
    jarFilename: `${modId}-${profile.minecraft}-${profile.loader}-${version}.jar` };
}

/** gradle.properties must agree with the profile; the Java toolchain is checked by Gradle itself. */
export function checkGradleProperties(profile, text) {
  const value = key => propertyValue(text, key);
  requireThat(value('minecraft_version') === profile.minecraft,
    `minecraft_version ${value('minecraft_version')} does not match profile ${profile.name}`);
  const loaderKey = profile.loader === 'forge' ? 'forge_version' : 'neo_version';
  requireThat(value(loaderKey), `Profile ${profile.name} requires ${loaderKey} in gradle.properties`);
  const otherKey = profile.loader === 'forge' ? 'neo_version' : 'forge_version';
  requireThat(!value(otherKey), `${otherKey} conflicts with profile ${profile.name}`);
}

/** Writable Gradle caches only on the trusted maintenance branch and the profile's release branch. */
export function ciOutputs(profile, refName) {
  return { profile: profile.name, java: String(profile.java), 'release-branch': profile.releaseBranch,
    'cache-write': String(refName === 'main' || refName === profile.releaseBranch) };
}

export function checkReleaseBranch(profile, refName) {
  requireThat(refName === profile.releaseBranch,
    `Releases of profile ${profile.name} run only from ${profile.releaseBranch}, not ${refName || '(none)'}`);
}

export function modProfile(root = '.') {
  return resolveMod(root).profile;
}

/** Resolve everything downstream build tools need, including the exact release identity. */
export function resolveMod(root = '.') {
  const config = JSON.parse(fs.readFileSync(path.join(root, '.ci/mod.json'), 'utf8'));
  const properties = fs.readFileSync(path.join(root, 'gradle.properties'), 'utf8');
  const profile = resolveProfile(config);
  checkGradleProperties(profile, properties);
  const version = propertyValue(properties, 'mod_version');
  requireThat(typeof config.modId === 'string' && config.modId, 'Invalid .ci/mod.json modId');
  requireThat(typeof version === 'string' && version, 'gradle.properties requires mod_version');
  return { profile, identity: releaseIdentity(profile, config.modId, version) };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [command, root = '.'] = process.argv.slice(2);
    if (command === 'resolve') {
      process.stdout.write(JSON.stringify(resolveMod(root)) + '\n');
    } else {
      const profile = modProfile(root);
      const refName = process.env.GITHUB_REF_NAME;
      if (command === 'outputs') {
        const lines = Object.entries(ciOutputs(profile, refName)).map(([k, v]) => `${k}=${v}`).join('\n') + '\n';
        if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT, lines);
        process.stdout.write(lines);
      } else if (command === 'check-release-branch') {
        checkReleaseBranch(profile, refName);
        console.log(`Release branch ${refName} matches profile ${profile.name}`);
      } else throw Error('Expected resolve, outputs or check-release-branch');
    }
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
