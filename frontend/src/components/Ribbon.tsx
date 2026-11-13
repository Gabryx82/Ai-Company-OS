import { useId } from "react";

/**
 * The console's one use of light: a ribbon of fine blue lines sweeping across a
 * dark field, drawn as geometry. It lives behind the dashboard welcome, the
 * system card in the sidebar and the sign-in, nowhere else. With `animate` the
 * lines draw in once when the view opens.
 */
export function Ribbon({ className = "ribbon", animate = false }: { className?: string; animate?: boolean }) {
  const id = useId().replace(/:/g, "");
  const lines = Array.from({ length: 16 }, (_, i) => {
    const y0 = 430 - i * 7;
    const c1 = 380 - i * 5;
    const c2 = 150 + i * 9;
    const y3 = -30 + i * 11;
    return `M -60 ${y0} C 170 ${c1}, 330 ${c2}, 680 ${y3}`;
  });
  return (
    <svg className={`${className}${animate ? " is-drawing" : ""}`} viewBox="0 0 600 400" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <defs>
        <linearGradient id={`${id}-line`} x1="0" y1="1" x2="1" y2="0">
          <stop offset="0" style={{ stopColor: "var(--accent)", stopOpacity: 0 }} />
          <stop offset="0.38" style={{ stopColor: "var(--accent)", stopOpacity: 0.55 }} />
          <stop offset="0.62" style={{ stopColor: "var(--ribbon-core)", stopOpacity: 0.95 }} />
          <stop offset="0.86" style={{ stopColor: "var(--accent)", stopOpacity: 0.4 }} />
          <stop offset="1" style={{ stopColor: "var(--accent)", stopOpacity: 0 }} />
        </linearGradient>
        <filter id={`${id}-soft`} x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="22" />
        </filter>
      </defs>
      <path d={lines[8]} style={{ stroke: "var(--accent)", strokeOpacity: "var(--ribbon-haze)" }} strokeWidth="70" fill="none"
            filter={`url(#${id}-soft)`} />
      {lines.map((d, i) => (
        <path key={i} className="ribbon-line" d={d} fill="none" stroke={`url(#${id}-line)`}
              strokeWidth={i % 4 === 0 ? 1.4 : 0.8} strokeOpacity={0.35 + (i % 5) * 0.13}
              style={{ animationDelay: `${i * 35}ms` }} />
      ))}
    </svg>
  );
}
