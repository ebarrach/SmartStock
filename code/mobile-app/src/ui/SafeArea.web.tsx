import React from 'react';
export { View as SafeAreaView } from 'react-native';
export function SafeAreaProvider({ children }: React.PropsWithChildren) {
  return <>{children}</>;
}
