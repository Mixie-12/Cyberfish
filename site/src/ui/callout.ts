/**
 * Callout for the board inside the DeKoi unit: a dot on the part, a leader
 * dropping out of the bottom of the box (leaning a little toward the
 * middle of the frame), and the label centred under its end, so it never
 * reaches across to the copy beside the unit. The scene feeds it the
 * part's screen position every frame; when there is no room below, the
 * leader goes up and the label sits above instead. Drawn in once told the
 * lid is open.
 */

export interface CalloutPoint {
  /** CSS pixels, relative to the viewport. */
  x: number;
  y: number;
  /** The lid is open: draw the callout in. */
  open: boolean;
}

/** Leader: how far it drops (clear of the open unit) and leans, in CSS pixels. */
const LEG_X = 36;
const LEG_Y = 170;
const LABEL_GAP = 14;
/** Least distance between the label and the viewport edge. */
const EDGE_MARGIN = 20;

export class Callout {
  private readonly root: HTMLElement;
  private readonly leader: SVGPolylineElement;
  private readonly dot: SVGCircleElement;
  private readonly label: HTMLElement;
  private on = false;

  constructor(root: HTMLElement) {
    this.root = root;
    this.leader = root.querySelector('.callout-leader') as SVGPolylineElement;
    this.dot = root.querySelector('.callout-dot') as SVGCircleElement;
    this.label = root.querySelector('.callout-label') as HTMLElement;
  }

  /** Place the callout for this frame, or clear it (`null`) when the deep is gone. */
  place(p: CalloutPoint | null): void {
    if (!p) {
      this.setOn(false);
      return;
    }
    const w = window.innerWidth;
    const h = window.innerHeight;
    const lw = this.label.offsetWidth;
    const lh = this.label.offsetHeight;
    // Down and out of the box, unless the label would then leave the frame.
    const dy = p.y + LEG_Y + LABEL_GAP + lh + EDGE_MARGIN > h ? -1 : 1;
    // Leaning a little toward the middle of the frame.
    const dx = p.x > w * 0.5 ? -1 : 1;
    const ey = p.y + dy * LEG_Y;
    const ex = p.x + dx * LEG_X;

    // Label centred under (or over) the leader's end, kept inside the frame.
    const lx = Math.min(Math.max(ex - lw / 2, EDGE_MARGIN), w - EDGE_MARGIN - lw);
    const ly = dy > 0 ? ey + LABEL_GAP : ey - LABEL_GAP - lh;

    this.dot.setAttribute('cx', p.x.toFixed(1));
    this.dot.setAttribute('cy', p.y.toFixed(1));
    this.leader.setAttribute(
      'points',
      `${p.x.toFixed(1)},${p.y.toFixed(1)} ${ex.toFixed(1)},${ey.toFixed(1)}`,
    );
    this.label.style.transform = `translate(${lx.toFixed(1)}px, ${ly.toFixed(1)}px)`;

    this.setOn(p.open);
  }

  private setOn(on: boolean): void {
    if (on === this.on) return;
    this.on = on;
    this.root.classList.toggle('on', on);
  }
}
