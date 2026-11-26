import React from 'react';
import { Pressable, Text, View } from 'react-native';
import { LayoutDashboard, Package, ArrowLeftRight, Bell } from './icons';
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
  { name: 'Mouvements', icon: ArrowLeftRight },
  { name: 'Alertes', icon: Bell },
] as const;
export function Navigation({
  wide,
  active,
  onSelect,
}: {
  wide: boolean;
  active: Destination;
  onSelect: (s: Destination) => void;
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
      {items.map(({ name, icon: Icon }) => (
        <Pressable
          key={name}
          accessibilityRole="tab"
          accessibilityState={{ selected: active === name }}
          accessibilityLabel={name}
          onPress={() => onSelect(name)}
          style={{
            minHeight: 56,
            flex: wide ? undefined : 1,
            flexDirection: wide ? 'row' : 'column',
            alignItems: 'center',
            justifyContent: wide ? 'flex-start' : 'center',
            padding: 8,
            gap: 6,
            borderRadius: 10,
            backgroundColor: active === name ? '#087e7a' : 'transparent',
          }}
        >
          <Icon size={21} color="white" />
          <Text
            style={{ color: 'white', fontSize: wide ? 14 : 10, flexShrink: 1 }}
          >
            {name === 'Tableau de bord' && !wide ? 'Accueil' : name}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}
