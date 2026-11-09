/**
 * Loading overlay. Port of reference/loading-screen.html.
 *
 * The markup and styles live in index.html so the overlay paints before any
 * module script is fetched. This class only drives it: callers report real
 * progress via setProgress(); the shown value eases toward it so it never
 * jumps. Progress is shown by the mark filling and the wordmark typing out.
 * At 100% the mark lifts to make room and two enter buttons appear below;
 * the overlay fades once the visitor picks sound on or off.
 */

// Mark viewBox x-range; the clip rect sweeps across this.
const X0 = 30;
const X1 = 1650;
const W = X1 - X0;

const EASE = 0.08;
/**
 * Wordmark typed out one letter per 1/9th of progress, so the final letter
 * lands together with the bar reaching 100%.
 */
const WORDMARK = 'CYBERFISH';
/**
 * Minimum time from navigation start to 100%. Real progress still drives the
 * bar, but it is paced so a fast (or local) load still reads as a load.
 */
const MIN_LOAD_MS = 2000;
const READY_DELAY_MS = 500;
/** Buttons animate with the lift (`--rise-ms` in index.html), no delay. */
const ENTER_READY_MS = 1600;
const FADE_MS = 600;

/** What the visitor chose on the enter buttons. */
export interface EnterChoice {
  sound: boolean;
}

function el<T extends Element>(id: string): T {
  const node = document.getElementById(id);
  if (!node) throw new Error(`#${id} missing from loader markup`);
  return node as unknown as T;
}

export class Loader {
  /** Resolves once the visitor has entered and the overlay has been removed. */
  readonly done: Promise<EnterChoice>;

  private readonly root = el<HTMLElement>('loader');
  private readonly clip = el<SVGRectElement>('cliprect');
  private readonly halo = el<SVGUseElement>('halo');
  private readonly edge = el<SVGGElement>('edge');
  private readonly edgeBlur = el<SVGRectElement>('edgeblur');
  private readonly edgeLine = el<SVGRectElement>('edgeline');
  private readonly typed = el<HTMLElement>('typed');
  private readonly enterSound = el<HTMLButtonElement>('enter-sound');
  private readonly enterMute = el<HTMLButtonElement>('enter-mute');

  private readonly reducedMotion: boolean;
  private target = 0;
  private shown = 0;
  private finished = false;
  private entered = false;
  private resolveDone!: (choice: EnterChoice) => void;

  constructor(reducedMotion: boolean) {
    this.reducedMotion = reducedMotion;
    this.done = new Promise((resolve) => (this.resolveDone = resolve));
    this.enterSound.addEventListener('click', () => this.enter(true));
    this.enterMute.addEventListener('click', () => this.enter(false));
    this.render(0);
    requestAnimationFrame(this.tick);
  }

  /** Report real progress in 0..1. Never moves backwards. */
  setProgress(p: number): void {
    this.target = Math.max(this.target, Math.min(1, p));
  }

  private readonly tick = (): void => {
    // performance.now() counts from navigation start, so the pacing covers the
    // HTML and module fetch as well as the work reported via setProgress().
    const pace = Math.min(1, performance.now() / MIN_LOAD_MS);
    const target = Math.min(this.target, pace);
    if (this.reducedMotion) {
      this.shown = target;
    } else {
      this.shown += (target - this.shown) * EASE;
      if (target >= 1 && this.shown > 0.995) this.shown = 1;
    }
    this.render(this.shown);
    if (this.shown < 1) requestAnimationFrame(this.tick);
    else if (!this.finished) this.finish();
  };

  private render(p: number): void {
    const x = X0 + W * p;
    this.clip.setAttribute('width', String(x - X0));
    this.edgeBlur.setAttribute('x', String(x - 7));
    this.edgeLine.setAttribute('x', String(x - 3));

    let fade = p > 0.85 ? 1 - (p - 0.85) / 0.15 : 1;
    if (this.reducedMotion) fade = 0;
    this.halo.setAttribute('opacity', (0.9 * fade).toFixed(3));
    this.edge.setAttribute('opacity', fade.toFixed(3));

    // floor() so the word only completes at exactly 100%; textContent is only
    // touched when the letter count changes to avoid needless relayout.
    const typed = WORDMARK.slice(0, Math.floor(p * WORDMARK.length));
    if (this.typed.textContent !== typed) this.typed.textContent = typed;
  }

  private finish(): void {
    this.finished = true;

    // `ready` lifts the mark and wordmark (same size) and reveals the enter
    // buttons below (styles in index.html). Under reduced motion nothing moves
    // and the buttons simply fade in.
    window.setTimeout(() => {
      this.root.classList.add('ready');
      const focusDelay = this.reducedMotion ? 0 : ENTER_READY_MS;
      window.setTimeout(() => {
        if (!this.entered) this.enterSound.focus({ preventScroll: true });
      }, focusDelay);
    }, READY_DELAY_MS);
  }

  private enter(sound: boolean): void {
    if (this.entered) return;
    this.entered = true;
    this.enterSound.disabled = true;
    this.enterMute.disabled = true;

    this.root.classList.add('done');
    window.setTimeout(() => {
      this.root.remove();
      this.resolveDone({ sound });
    }, FADE_MS);
  }
}
