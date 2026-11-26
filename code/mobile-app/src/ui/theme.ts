/** Shared visual tokens from the team's dashboard reference. */
export const theme = {
  colors: {
    navy: '#071e30',
    navigation: '#073646',
    ink: '#112039',
    muted: '#71809a',
    teal: '#00bb93',
    green: '#00b780',
    blue: '#1599f3',
    violet: '#9275f7',
    red: '#ef4e59',
    orange: '#eb9e35',
    border: '#e3ebf2',
    background: '#f5f8fc',
    surface: '#ffffff',
  },
  radius: 16,
  minimumTouchTarget: 48,
} as const;

export function layoutForWidth(width: number) {
  return {
    navigation: width >= 768 ? ('sidebar' as const) : ('bottom' as const),
    columns: width >= 1180 ? 4 : width >= 768 ? 2 : 1,
    horizontalPadding: width >= 768 ? 28 : 16,
  };
}
