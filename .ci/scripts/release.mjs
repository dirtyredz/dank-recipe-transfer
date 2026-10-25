import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const sha256 = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const requireThat = (condition, message) => { if (!condition) throw Error(message); };
export function validateManifest(m) {
  requireThat(/^[a-z][a-z0-9_]*$/.test(m.modId) && typeof m.name === 'string' && m.name.trim(), 'Invalid mod identity');
  requireThat(/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?$/.test(m.version), 'Expected semantic mod_version');
  requireThat(m.tag === 'v' + m.version && m.prerelease === (m.version.startsWith('0.') || m.version.includes('-')), 'Invalid resolved release policy');
  requireThat(['both', 'client', 'server'].includes(m.side) && /^[A-Za-z0-9_.+-]+\.jar$/.test(m.jarFilename), 'Invalid release asset identity');
  return m;
}
function notes(m, root) {
  const file = path.join(root, 'docs/releases', m.version + '.md');
  requireThat(fs.readFileSync(file, 'utf8').trim(), 'Release notes must be non-empty');
  return file;
}
export function prepareAssets(m, dir, root, server, repo) {
  validateManifest(m); notes(m, root);
  const jars = fs.readdirSync(dir).filter(name => name.endsWith('.jar'));
  requireThat(jars.length === 1 && jars[0] === m.jarFilename, 'Expected exactly one verified release jar');
  const digest = sha256(path.join(dir, m.jarFilename));
  fs.writeFileSync(path.join(dir, m.jarFilename + '.sha256'), `${digest}  ${m.jarFilename}\n`);
  const url = `${server}/${repo}/releases/download/${encodeURIComponent(m.tag)}/${encodeURIComponent(m.jarFilename)}`;
  const q = JSON.stringify;
  fs.writeFileSync(path.join(dir, m.modId + '.pw.toml'),
    `name = ${q(m.name)}\nfilename = ${q(m.jarFilename)}\nside = ${q(m.side)}\n\n[download]\nurl = ${q(url)}\nhash-format = "sha256"\nhash = ${q(digest)}\n`);
}
export function releasePolicy(m, releases, refs, sha) {
  validateManifest(m);
  const matches = releases.filter(r => r.tag_name === m.tag);
  requireThat(matches.length === 0 || (matches.length === 1 && matches[0].draft === true), 'Expected exactly one private draft');
  const tags = refs.filter(r => r.ref === 'refs/tags/' + m.tag);
  requireThat(tags.length === 0 || (tags.length === 1 && /^[0-9a-f]{40}$/.test(sha)
    && tags[0].object?.type === 'commit' && tags[0].object.sha === sha), 'Existing tag must point at exact built commit');
  requireThat(matches.length === 0 || tags.length === 1, 'Cannot resume draft without matching tag');
  const numeric = tag => {
    requireThat(/^v\d+\.\d+\.\d+$/.test(tag), 'Cannot order stable release: ' + tag);
    return tag.slice(1).split('.').map(BigInt);
  };
  const greater = (a, b) => { for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] > b[i]; return false; };
  const stable = releases.filter(r => !r.draft && !r.prerelease);
  return { latest: !m.prerelease && stable.every(r => greater(numeric(m.tag), numeric(r.tag_name))),
    draft: matches[0], tagExists: tags.length === 1 };
}
export function publishRelease(m, dir, root, repo, sha, gh,
  sleep = ms => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms)) {
  validateManifest(m);
  requireThat(/^[0-9a-f]{40}$/.test(sha), 'Expected exact built commit');
  const notesFile = notes(m, root);
  const list = () => JSON.parse(gh('api', '--paginate', '--slurp', `repos/${repo}/releases?per_page=100`)).flat();
  const releases = list();
  const refs = JSON.parse(gh('api', `repos/${repo}/git/matching-refs/tags/${m.tag}`));
  // Peel annotated tags to the commit; only this exact tag matters, not prefix matches.
  for (const ref of refs.filter(r => r.ref === 'refs/tags/' + m.tag)) {
    for (let depth = 0; ref.object?.type === 'tag' && depth < 10; depth++) {
      requireThat(/^[0-9a-f]{40}$/.test(ref.object.sha), 'Invalid annotated tag object');
      ref.object = JSON.parse(gh('api', `repos/${repo}/git/tags/${ref.object.sha}`)).object;
    }
  }
  const { latest, draft, tagExists } = releasePolicy(m, releases, refs, sha);
  const names = [m.jarFilename, m.jarFilename + '.sha256', m.modId + '.pw.toml'].sort();
  requireThat(JSON.stringify(fs.readdirSync(dir).sort()) === JSON.stringify(names), 'Expected jar, checksum and packwiz assets');
  const expected = new Map(names.map(name => [name, sha256(path.join(dir, name))]));
  requireThat(fs.readFileSync(path.join(dir, m.jarFilename + '.sha256'), 'utf8') === `${expected.get(m.jarFilename)}  ${m.jarFilename}\n`, 'Local jar checksum mismatch');
  if (!tagExists) {
    // Create-only API fails on a racing tag; never move an existing ref.
    gh('api', '--method', 'POST', `repos/${repo}/git/refs`, '-f', 'ref=refs/tags/' + m.tag, '-f', 'sha=' + sha);
  }
  let release = draft;
  if (!release) {
    gh('release', 'create', m.tag, ...names.map(name => path.join(dir, name)), '--verify-tag', '--draft', '--latest=false',
      ...(m.prerelease ? ['--prerelease'] : []), '--title', m.tag, '--notes-file', notesFile);
    // Lists can lag creation. Poll only absence; ambiguous/public results fail immediately.
    for (let attempt = 0; attempt < 10; attempt++) {
      const matches = list().filter(r => r.tag_name === m.tag);
      if (matches.length) {
        requireThat(matches.length === 1 && matches[0].draft === true, 'Expected exactly one private draft');
        release = matches[0];
        break;
      }
      if (attempt < 9) sleep(3000);
    }
    requireThat(release, 'Expected exactly one private draft after 10 read-back attempts');
  }
  requireThat(JSON.stringify(release.assets.map(a => a.name).sort()) === JSON.stringify(names), 'Unexpected release asset set');
  const readback = fs.mkdtempSync(path.join(root, '.release-readback-'));
  try {
    gh('release', 'download', m.tag, '--dir', readback);
    for (const [name, digest] of expected) requireThat(sha256(path.join(readback, name)) === digest, 'Readback SHA-256 mismatch: ' + name);
    gh('api', '--method', 'PATCH', `repos/${repo}/releases/${release.id}`, '-F', 'draft=false',
      '-F', 'prerelease=' + m.prerelease, '-f', 'make_latest=' + latest);
  } finally { fs.rmSync(readback, {recursive:true, force:true}); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [command, manifestFile, dir] = process.argv.slice(2);
    const m = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
    if (command === 'prepare') prepareAssets(m, dir, '.', process.env.GITHUB_SERVER_URL, process.env.GITHUB_REPOSITORY);
    else if (command === 'publish') {
      publishRelease(m, dir, '.', process.env.GH_REPO, process.env.GITHUB_SHA,
        (...args) => execFileSync('gh', args, {encoding:'utf8', stdio:['ignore','pipe','inherit']}));
      console.log('Published ' + m.tag + ' after SHA-256 readback of every asset');
    } else throw Error('Expected prepare or publish');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
