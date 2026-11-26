import React from 'react';
import type {
  SvgProps,
  RectProps,
  LinearGradientProps,
  StopProps,
} from 'react-native-svg';
// The shared chart uses this small SVG subset. Web renders standard SVG rather
// than loading native Fabric modules; geometry and interaction remain shared.
const scalar = (v: unknown) =>
  typeof v === 'number' || typeof v === 'string' ? v : undefined;
export default function Svg({
  children,
  width,
  height,
  viewBox,
  style,
  accessible,
  pointerEvents,
}: SvgProps) {
  return (
    <svg
      width={scalar(width)}
      height={scalar(height)}
      viewBox={viewBox}
      style={style as React.CSSProperties}
      aria-hidden={accessible === false}
      pointerEvents={pointerEvents}
    >
      {children}
    </svg>
  );
}
export function Rect({ x, y, width, height, rx, fill, stroke }: RectProps) {
  return (
    <rect
      x={scalar(x)}
      y={scalar(y)}
      width={scalar(width)}
      height={scalar(height)}
      rx={scalar(rx)}
      fill={scalar(fill) as string}
      stroke={scalar(stroke) as string}
    />
  );
}
export function Defs({ children }: React.PropsWithChildren) {
  return <defs>{children}</defs>;
}
export function LinearGradient({
  children,
  id,
  x1,
  y1,
  x2,
  y2,
}: LinearGradientProps) {
  return (
    <linearGradient
      id={id}
      x1={scalar(x1)}
      y1={scalar(y1)}
      x2={scalar(x2)}
      y2={scalar(y2)}
    >
      {children}
    </linearGradient>
  );
}
export function Stop({ offset, stopColor }: StopProps) {
  return (
    <stop offset={scalar(offset)} stopColor={scalar(stopColor) as string} />
  );
}
