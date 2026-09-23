import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

// Tests the actual lifecycle/component source. No browser login, media network or API.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const out = path.join(root, 'artifacts/frontend-polish-20260907');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = fs.readFileSync(path.join(root, 'src/pages/login.jsx'), 'utf8');
const before = fs.readFileSync(path.join(out, 'checkpoint/src/pages/login.jsx'), 'utf8');
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const compiled = Babel.transform(source, { presets: ['react'], ast: true });
const oldAST = Babel.transform(before, { presets: ['react'], ast: true }).ast;
const results = [];
const startedAt = new Date().toISOString();
function check(name, fn) {
  try { const details = fn(); results.push({ name, pass: true, details }); console.log('PASS ' + name); }
  catch (error) { results.push({ name, pass: false, error: error.stack }); console.error('FAIL ' + name + ': ' + error.message); }
}
function target(properties = {}) {
  const listeners = new Map();
  return Object.assign({
    addEventListener(type, fn) { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(fn); },
    removeEventListener(type, fn) { listeners.get(type)?.delete(fn); },
    emit(type) { for (const fn of [...(listeners.get(type) || [])]) fn(); },
    listenerCount() { return [...listeners.values()].reduce((sum, set) => sum + set.size, 0); },
  }, properties);
}
function environment({ reduced = false, hidden = false, legacy = false } = {}) {
  const mediaQuery = target({ matches: reduced });
  if (legacy) { const add = mediaQuery.addEventListener.bind(mediaQuery), remove = mediaQuery.removeEventListener.bind(mediaQuery); mediaQuery.addListener = fn => add('change', fn); mediaQuery.removeListener = fn => remove('change', fn); delete mediaQuery.addEventListener; delete mediaQuery.removeEventListener; }
  const doc = target({ hidden });
  const frames = new Map(); let sequence = 0, draws = 0, hooks = [], index = 0, effects = [];
  const context = vm.createContext({
    window: { matchMedia: () => mediaQuery }, document: doc, WeakMap, console,
    requestAnimationFrame(fn) { const id = ++sequence; frames.set(id, fn); return id; },
    cancelAnimationFrame(id) { frames.delete(id); },
    React: {
      useRef(value) { const i = index++; return hooks[i] ||= { current: value }; },
      useEffect(fn) { effects.push(fn); },
      createElement(type, props, ...children) { return { type, props: props || {}, children }; },
      Fragment: 'fragment',
    },
  });
  vm.runInContext(compiled.code, context);
  return {
    context, mediaQuery, doc, frames,
    draw: () => { draws++; }, get draws() { return draws; },
    start(options) { context.options = options; return vm.runInContext('createLoginDecorationLoop(options)', context); },
    reduce(value) { mediaQuery.matches = value; mediaQuery.emit('change'); },
    hide(value) { doc.hidden = value; doc.emit('visibilitychange'); },
    frame() { const callbacks = [...frames.values()]; frames.clear(); callbacks.forEach(fn => fn()); },
    mount(name, props, refs) { hooks = refs.map(current => ({ current })); index = 0; effects = []; context.props = props; const tree = vm.runInContext(name + '(props)', context); return { tree, cleanup: effects.map(fn => fn()) }; },
  };
}
function video() {
  const pending = [];
  const result = target({ readyState: 2, videoWidth: 2, videoHeight: 2, currentTime: 0, muted: true, volume: 0, paused: true, plays: 0, pauses: 0,
    pause() { this.paused = true; this.pauses++; },
    play() { this.plays++; this.paused = false; return { then: resolve => pending.push(resolve) }; },
    resolve(index = 0) { this.paused = false; pending[index]?.(); },
  });
  return result;
}
function canvas() {
  const draws = [];
  return { width: 0, height: 0, draws, getContext() { return { drawImage: media => draws.push(media), clearRect() {}, getImageData: () => ({ data: new Uint8ClampedArray(16) }), putImageData() {} }; } };
}
function clean(node) {
  if (Array.isArray(node)) return node.map(clean);
  if (!node || typeof node !== 'object') return node;
  return Object.fromEntries(Object.entries(node).filter(([key]) => !['start','end','loc','extra','leadingComments','trailingComments','innerComments'].includes(key)).map(([key, value]) => [key, clean(value)]));
}
const body = ast => ast.program.body;
const find = (ast, name) => body(ast).find(item => item.type === 'FunctionDeclaration' && item.id.name === name);

check('all source parses; chroma JSX layout and nondecorative module declarations remain unchanged', () => {
  for (const name of ['ChromaVideo','ChromaGif','LoginPandaMascot']) {
    const returned = ast => find(ast, name).body.body.find(item => item.type === 'ReturnStatement');
    assert.deepEqual(clean(returned(compiled.ast)), clean(returned(oldAST)), name);
  }
  const excluded = new Set(['ChromaVideo','ChromaGif','LoginPandaMascot','LoginPage']);
  for (const declaration of body(oldAST)) {
    if (declaration.type !== 'FunctionDeclaration' || excluded.has(declaration.id.name)) continue;
    assert.deepEqual(clean(find(compiled.ast, declaration.id.name)), clean(declaration), declaration.id.name);
  }
  return { baseline: hash(before), source: hash(source) };
});
check('login authentication/captcha/clock/business logic unchanged; background replacement preserves geometry', () => {
  const normalizedBefore = before.replace(/\r\n/g, '\n');
  const oldPage = normalizedBefore.slice(normalizedBefore.indexOf('const LOCAL_CAPTCHA_ALPHABET'));
  const replacement = oldPage.replace(/        <video\n[\s\S]*?src="assets\/login-bg\.mp4"[\s\S]*?        \/>/, '        <LoginBackgroundVideo />');
  // Index is computed on the normalized string to avoid Windows line-ending drift.
  const normalizedSource = source.replace(/\r\n/g, '\n');
  assert.equal(normalizedSource.slice(normalizedSource.indexOf('const LOCAL_CAPTCHA_ALPHABET')), replacement);
  const helper = find(compiled.ast, 'LoginBackgroundVideo'); assert.ok(helper);
  const env = environment(); const rendered = env.mount('LoginBackgroundVideo', {}, [video()]);
  assert.equal(rendered.tree.type, 'video'); assert.equal(rendered.tree.props.src, 'assets/login-bg.mp4');
  assert.deepEqual({ ...rendered.tree.props.style }, { top:-160,height:'calc(100% + 160px)',left:0,right:0,objectFit:'cover',objectPosition:'center' });
  assert.equal(rendered.tree.props.autoPlay, undefined); rendered.cleanup.forEach(fn => fn?.());
});
check('initial reduced mode displays static canvas without RAF or playback', () => {
  const env = environment({ reduced: true }), media = video(); const stop = env.start({ draw: env.draw, media: [media], activeVideo: media });
  assert.equal(env.frames.size, 0); assert.equal(media.plays, 0); assert.equal(media.paused, true); assert.equal(env.draws, 1);
  media.emit('loadeddata'); assert.equal(env.draws, 2); assert.equal(env.frames.size, 0); stop();
});
check('live preference and visibility changes maintain exactly one active RAF', () => {
  const env = environment(), media = video(), stop = env.start({ draw: env.draw, media: [media], activeVideo: media });
  assert.equal(env.frames.size, 1); env.frame(); assert.equal(env.frames.size, 1);
  env.reduce(true); assert.equal(env.frames.size, 0); assert.equal(media.paused, true);
  env.reduce(false); assert.equal(env.frames.size, 1); env.reduce(false); env.hide(false); assert.equal(env.frames.size, 1); assert.equal(media.plays, 2);
  env.hide(true); const draws = env.draws; assert.equal(env.frames.size, 0); media.emit('loadeddata'); assert.equal(env.draws, draws); assert.equal(media.paused, true);
  env.hide(false); assert.equal(env.frames.size, 1); assert.equal(media.plays, 3); stop();
});
check('initial hidden and explicitly disabled decorations never start video or RAF', () => {
  for (const options of [{ hidden:true }, {}]) { const env = environment(options), media = video(); const stop = env.start({ draw: env.draw, media:[media],activeVideo:media,enabled:!!options.hidden }); assert.equal(env.frames.size,0);assert.equal(media.plays,0);stop(); }
});
check('unmount removes all listeners and cancels a pending or already captured RAF callback', () => {
  const env = environment(), media = video(), stop = env.start({ draw:env.draw,media:[media],activeVideo:media });
  const queued = [...env.frames.values()][0]; stop(); const count = env.draws; queued(); media.emit('loadeddata'); env.reduce(false); env.hide(false);
  assert.equal(env.frames.size,0);assert.equal(env.draws,count);assert.equal(env.doc.listenerCount(),0);assert.equal(env.mediaQuery.listenerCount(),0);assert.equal(media.listenerCount(),0);assert.equal(media.paused,true);
});
check('late play promises are paused after hidden/reduced/unmounted transitions', () => {
  for (const transition of ['hidden','reduced','unmount']) { const env=environment(), media=video(),stop=env.start({draw:env.draw,media:[media],activeVideo:media}); if(transition==='hidden')env.hide(true);else if(transition==='reduced')env.reduce(true);else stop();media.resolve();assert.equal(media.paused,true,transition);assert.equal(env.frames.size,0);stop(); }
});
check('late old effect promise cannot pause the replacement effect using the same media', () => {
  const env=environment(), media=video();const first=env.start({draw:env.draw,media:[media],activeVideo:media});first();const second=env.start({draw:env.draw,media:[media],activeVideo:media});media.resolve(0);assert.equal(media.paused,false);assert.equal(env.frames.size,1);second();
});
check('legacy MediaQueryList listeners are supported and cleaned up', () => {
  const env=environment({legacy:true}),media=video(),stop=env.start({draw:env.draw,media:[media],activeVideo:media});env.reduce(true);assert.equal(env.frames.size,0);stop();assert.equal(env.mediaQuery.listenerCount(),0);
});
check('background media uses no RAF; first valid gesture unmutes only in normal visible mode', () => {
  const env=environment({reduced:true}),media=video(),stop=env.start({media:[media],activeVideo:media,unmuteOnGesture:true});
  env.doc.emit('click');assert.equal(media.muted,true);assert.equal(media.plays,0);env.reduce(false);assert.equal(media.plays,1);assert.equal(env.frames.size,0);
  env.hide(true);env.doc.emit('keydown');assert.equal(media.muted,true);assert.equal(media.plays,1);env.hide(false);env.doc.emit('keydown');assert.equal(media.muted,false);assert.equal(media.volume,.8);assert.equal(media.plays,3);
  env.doc.emit('click');assert.equal(media.plays,3);stop();assert.equal(env.doc.listenerCount(),0);media.resolve(2);assert.equal(media.paused,true);
});
check('actual ChromaVideo and ChromaGif components wire lifecycle and static media readiness', () => {
  const env=environment({reduced:true}),media=video(),c=canvas();const mounted=env.mount('ChromaVideo',{src:'test.mp4',playing:true},[media,c,false]);assert.equal(env.frames.size,0);assert.equal(media.plays,0);assert.ok(c.draws.length);env.reduce(false);assert.equal(env.frames.size,1);mounted.cleanup.forEach(fn=>fn?.());assert.equal(env.frames.size,0);
  const img=target({complete:false,naturalWidth:0,naturalHeight:0}),cg=canvas();env.reduce(true);const gif=env.mount('ChromaGif',{src:'test.gif'},[img,cg]);assert.equal(cg.draws.length,0);img.complete=true;img.naturalWidth=2;img.naturalHeight=2;img.emit('load');assert.equal(cg.draws.length,1);assert.equal(env.frames.size,0);gif.cleanup.forEach(fn=>fn?.());
});
check('actual panda component plays only selected video and pauses both on cleanup', () => {
  const env=environment(),idle=video(),cover=video(),c=canvas();const normal=env.mount('LoginPandaMascot',{covering:false},[idle,cover,c]);assert.equal(idle.paused,false);assert.equal(cover.paused,true);assert.equal(c.draws.at(-1),idle);normal.cleanup.forEach(fn=>fn?.());
  cover.currentTime=4;const focused=env.mount('LoginPandaMascot',{covering:true},[idle,cover,c]);assert.equal(cover.currentTime,0);assert.equal(idle.paused,true);assert.equal(cover.paused,false);assert.equal(c.draws.at(-1),cover);env.reduce(true);assert.equal(env.frames.size,0);assert.equal(cover.paused,true);focused.cleanup.forEach(fn=>fn?.());assert.equal(idle.paused,true);assert.equal(cover.paused,true);
});

fs.mkdirSync(out,{recursive:true});
const report={suite:'login-decoration-lifecycle',startedAt,finishedAt:new Date().toISOString(),sourceHash:hash(source),baselineHash:hash(before),results,passed:results.filter(item=>item.pass).length,total:results.length,scope:'Isolated VM and actual source AST. No backend/auth calls or real login session.'};
fs.writeFileSync(path.join(out,'login-decoration-results.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({passed:report.passed,total:report.total}));
if(report.passed!==report.total)process.exitCode=1;
