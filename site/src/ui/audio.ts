/**
 * UI sound effects. Web Audio for low-latency one-shots; buffers are fetched
 * early and decoded on first use. Nothing plays unless the visitor opted in,
 * and the preference is remembered in localStorage and mirrored on
 * `html[data-sound]` for CSS.
 */
export type SfxName = 'hover' | 'confirm1' | 'confirm2' | 'error' | 'dive';

const FILES: Record<SfxName, string> = {
  hover: '/sfx/hover.mp3',
  confirm1: '/sfx/confirm-1.mp3',
  confirm2: '/sfx/confirm-2.mp3',
  error: '/sfx/error.mp3',
  dive: '/sfx/dive.mp3',
};

const GAIN: Record<SfxName, number> = {
  hover: 0.7,
  confirm1: 0.7,
  confirm2: 0.7,
  error: 0.6,
  dive: 0.85,
};

const STORAGE_KEY = 'cyberfish.sound';

/** Background track: "Digital Horizons", looped. */
const MUSIC_URL = '/sfx/music.mp3';
const MUSIC_GAIN = 0.45;
const MUSIC_FADE_IN = 2.5;
const MUSIC_FADE_OUT = 0.6;

export class Sfx {
  private ctx: AudioContext | null = null;
  private master: GainNode | null = null;
  private readonly raw = new Map<string, Promise<ArrayBuffer>>();
  private readonly buffers = new Map<string, Promise<AudioBuffer>>();
  private readonly listeners = new Set<(on: boolean) => void>();
  private on = false;
  /** Throttle for hover: at most one per 80 ms so a fast sweep does not stutter. */
  private lastHover = 0;
  /** Music: wanted (the page has been entered) and, if playing, its nodes. */
  private musicWanted = false;
  private music: { src: AudioBufferSourceNode; gain: GainNode } | null = null;

  constructor() {
    this.apply(false);
  }

  get enabled(): boolean {
    return this.on;
  }

  /** Stored preference from a previous visit, if any. */
  static storedPreference(): boolean | null {
    try {
      const v = localStorage.getItem(STORAGE_KEY);
      return v === null ? null : v === 'on';
    } catch {
      return null;
    }
  }

  /** Start downloading the files; call once the page is interactive. */
  preload(): void {
    for (const name of Object.keys(FILES) as SfxName[]) this.fetchRaw(FILES[name]);
    this.fetchRaw(MUSIC_URL);
  }

  /**
   * Turn sound on or off. Call from a user gesture when turning on. Turning
   * off fades the music out; it comes back via music() once something has
   * confirmed the switch.
   */
  setEnabled(on: boolean): void {
    if (on === this.on) return;
    this.apply(on);
    try {
      localStorage.setItem(STORAGE_KEY, on ? 'on' : 'off');
    } catch {
      /* private mode; ignore */
    }
    if (on) void this.context().resume();
    else this.stopMusic();
    for (const fn of this.listeners) fn(on);
  }

  /**
   * Start the background loop (fading in), or just remember that it is
   * wanted if sound is off. Idempotent while it plays.
   */
  startMusic(): void {
    this.musicWanted = true;
    this.resumeMusic();
  }

  /** Bring the music back after the sound was switched on again. */
  resumeMusic(): void {
    if (!this.musicWanted || !this.on || this.music) return;
    const ctx = this.context();
    const startedFor = (this.music = { src: ctx.createBufferSource(), gain: ctx.createGain() });
    startedFor.gain.gain.value = 0;
    startedFor.src.connect(startedFor.gain).connect(this.master!);
    void this.buffer(MUSIC_URL)
      .then((buffer) => {
        // Still wanted, and not superseded by a stop/start in between.
        if (this.music !== startedFor) return;
        const [start, end] = trimSilence(buffer);
        startedFor.src.buffer = buffer;
        startedFor.src.loop = true;
        startedFor.src.loopStart = start;
        startedFor.src.loopEnd = end;
        startedFor.src.start(0, start);
        const now = ctx.currentTime;
        startedFor.gain.gain.setValueAtTime(0, now);
        startedFor.gain.gain.linearRampToValueAtTime(MUSIC_GAIN, now + MUSIC_FADE_IN);
      })
      .catch(() => {
        if (this.music === startedFor) this.music = null;
      });
  }

  private stopMusic(): void {
    const m = this.music;
    if (!m) return;
    this.music = null;
    const ctx = this.context();
    const now = ctx.currentTime;
    m.gain.gain.cancelScheduledValues(now);
    m.gain.gain.setValueAtTime(m.gain.gain.value, now);
    m.gain.gain.linearRampToValueAtTime(0, now + MUSIC_FADE_OUT);
    try {
      m.src.stop(now + MUSIC_FADE_OUT + 0.05);
    } catch {
      /* never started (still decoding): nothing to stop */
    }
  }

  /**
   * Call synchronously inside a user gesture: creates and resumes the
   * context and decodes every buffer, so later plays are instant and Safari's
   * gesture requirement is met.
   */
  unlock(): void {
    const ctx = this.context();
    void ctx.resume();
    for (const name of Object.keys(FILES) as SfxName[]) {
      void this.buffer(FILES[name]).catch(() => {});
    }
    void this.buffer(MUSIC_URL).catch(() => {});
  }

  onChange(fn: (on: boolean) => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  /**
   * Play a one-shot. Resolves when it has finished (at once if sound is off
   * or it could not play), so the next thing can follow on.
   */
  play(name: SfxName): Promise<void> {
    if (!this.on) return Promise.resolve();
    if (name === 'hover') {
      const now = performance.now();
      if (now - this.lastHover < 80) return Promise.resolve();
      this.lastHover = now;
    }
    const ctx = this.context();
    if (ctx.state === 'suspended') void ctx.resume();
    return this.buffer(FILES[name]).then(
      (buffer) =>
        new Promise<void>((resolve) => {
          if (!this.on || !this.master) return resolve();
          const src = ctx.createBufferSource();
          src.buffer = buffer;
          const gain = ctx.createGain();
          gain.gain.value = GAIN[name];
          src.connect(gain).connect(this.master);
          src.onended = () => resolve();
          src.start();
        }),
      () => undefined,
    );
  }

  private apply(on: boolean): void {
    this.on = on;
    document.documentElement.dataset.sound = on ? 'on' : 'off';
  }

  private context(): AudioContext {
    if (!this.ctx) {
      this.ctx = new AudioContext();
      this.master = this.ctx.createGain();
      this.master.connect(this.ctx.destination);
    }
    return this.ctx;
  }

  private fetchRaw(url: string): Promise<ArrayBuffer> {
    let p = this.raw.get(url);
    if (!p) {
      p = fetch(url).then((r) => {
        if (!r.ok) throw new Error(`Audio ${url}: HTTP ${r.status}`);
        return r.arrayBuffer();
      });
      p.catch(() => this.raw.delete(url));
      this.raw.set(url, p);
    }
    return p;
  }

  private buffer(url: string): Promise<AudioBuffer> {
    let p = this.buffers.get(url);
    if (!p) {
      // decodeAudioData detaches the buffer, so hand it a copy.
      p = this.fetchRaw(url).then((raw) => this.context().decodeAudioData(raw.slice(0)));
      p.catch(() => this.buffers.delete(url));
      this.buffers.set(url, p);
    }
    return p;
  }
}

/**
 * Loop points that skip the near-silent padding MP3 encoders add at both
 * ends, so the loop is seamless. Returns [start, end] in seconds.
 */
function trimSilence(buffer: AudioBuffer, threshold = 0.002): [number, number] {
  const n = buffer.length;
  let first = n;
  let last = 0;
  for (let c = 0; c < buffer.numberOfChannels; c++) {
    const d = buffer.getChannelData(c);
    let i = 0;
    while (i < first && Math.abs(d[i]) < threshold) i++;
    first = Math.min(first, i);
    let j = n - 1;
    while (j > last && Math.abs(d[j]) < threshold) j--;
    last = Math.max(last, j);
  }
  if (first >= last) return [0, buffer.duration];
  return [first / buffer.sampleRate, (last + 1) / buffer.sampleRate];
}
