module.exports = {
  root: true,
  extends: '@react-native',
  ignorePatterns: ['dist/**'],
  overrides: [
    {
      files: ['src/ui/**/*.tsx', 'src/SmartStockApp.tsx'],
      // These small shared components compose responsive styles from runtime width and theme.
      rules: { 'react-native/no-inline-styles': 'off' },
    },
  ],
};
