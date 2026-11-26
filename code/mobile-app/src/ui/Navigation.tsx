import React from 'react';
import { Pressable, Text, View } from 'react-native';
import {
  LayoutDashboard,
  Package,
  ArrowLeftRight,
  Bell,
  ScanLine,
} from './icons';
import { theme } from './theme';
export type Destination =
  | 'Tableau de bord'
  | 'Stock'
  | 'Mouvements'
  | 'Alertes'
  | 'Connexion'
  | 'Démonstration'
  | 'Produit'
  | 'Graphiques'
  | 'Suggestions';
const items = [
  { name: 'Tableau de bord', icon: LayoutDashboard },
  { name: 'Stock', icon: Package },
  { name: 'Scanner', icon: ScanLine },
  { name: 'Mouvements', icon: ArrowLeftRight },
  { name: 'Alertes', icon: Bell },
] as const;
export function Navigation({
  wide,
  active,
  onSelect,
  onScan,
}: {
  wide: boolean;
  active: Destination;
  onSelect: (s: Destination) => void;
  onScan?: () => void;
}) {
  return (
    <View
      accessibilityLabel="Navigation principale"
      style={{
        flexDirection: wide ? 'column' : 'row',
        backgroundColor: theme.colors.navigation,
        padding: 8,
        gap: 4,
        ...(wide ? { width: 210 } : {}),
      }}
    >
      {items
        .filter(item => item.name !== 'Scanner' || onScan)
        .map(({ name, icon: Icon }) => (
          <Pressable
            key={name}
            accessibilityRole={name === 'Scanner' ? 'button' : 'tab'}
            accessibilityState={{ selected: active === name }}
            accessibilityLabel={name}
            onPress={() => (name === 'Scanner' ? onScan?.() : onSelect(name))}
            style={{
              minHeight: name === 'Scanner' ? 64 : 56,
              borderWidth: name === 'Scanner' ? 2 : 0,
              borderColor: '#67eee0',
              flex: wide ? undefined : 1,
              flexDirection: wide ? 'row' : 'column',
              alignItems: 'center',
              justifyContent: wide ? 'flex-start' : 'center',
              padding: 8,
              gap: 6,
              borderRadius: 10,
              backgroundColor:
                name === 'Scanner'
                  ? theme.colors.teal
                  : active === name
                  ? '#087e7a'
                  : 'transparent',
            }}
          >
            <Icon size={name === 'Scanner' ? 28 : 21} color="white" />
            <Text
              style={{
                color: 'white',
                fontSize: wide ? 14 : name === 'Scanner' ? 13 : 10,
                fontWeight: name === 'Scanner' ? '800' : '400',
                flexShrink: 1,
              }}
            >
              {name === 'Tableau de bord' && !wide ? 'Accueil' : name}
            </Text>
          </Pressable>
        ))}
    </View>
  );
}
