import React from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { theme } from './theme';
const c = theme.colors;
export function Card({ children }: React.PropsWithChildren) {
  return <View style={styles.card}>{children}</View>;
}
export function Action({
  label,
  onPress,
  disabled = false,
  secondary = false,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  secondary?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      disabled={disabled}
      onPress={onPress}
      style={[
        styles.button,
        secondary && styles.secondary,
        disabled && { opacity: 0.45 },
      ]}
    >
      <Text
        style={{ color: secondary ? c.navigation : 'white', fontWeight: '700' }}
      >
        {label}
      </Text>
    </Pressable>
  );
}
export function Field({
  label,
  value,
  onChangeText,
  numeric = false,
}: {
  label: string;
  value: string;
  onChangeText: (v: string) => void;
  numeric?: boolean;
}) {
  return (
    <View style={{ gap: 6 }}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        value={value}
        onChangeText={onChangeText}
        keyboardType={numeric ? 'numbers-and-punctuation' : 'default'}
        autoCapitalize="none"
        autoCorrect={false}
        style={styles.input}
      />
    </View>
  );
}
export function Message({
  text,
  error = false,
}: {
  text: string;
  error?: boolean;
}) {
  return (
    <Text
      accessibilityRole={error ? 'alert' : 'text'}
      style={{ color: error ? c.red : c.muted, lineHeight: 22 }}
    >
      {text}
    </Text>
  );
}
export function Loading() {
  return (
    <View style={{ padding: 20, gap: 12 }}>
      <ActivityIndicator color={c.teal} />
      <Message text="Chargement…" />
    </View>
  );
}
export function Title({ children }: React.PropsWithChildren) {
  return (
    <Text accessibilityRole="header" style={styles.title}>
      {children}
    </Text>
  );
}
export const styles = StyleSheet.create({
  card: {
    backgroundColor: c.surface,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: c.border,
    padding: 20,
    gap: 14,
  },
  button: {
    alignSelf: 'flex-start',
    minHeight: 48,
    paddingHorizontal: 18,
    paddingVertical: 13,
    borderRadius: 10,
    backgroundColor: c.navigation,
    alignItems: 'center',
    justifyContent: 'center',
  },
  secondary: { backgroundColor: '#e7f6f2' },
  input: {
    minHeight: 48,
    borderWidth: 1,
    borderColor: c.border,
    borderRadius: 10,
    padding: 12,
    color: c.ink,
    backgroundColor: c.surface,
  },
  label: { color: c.ink, fontWeight: '600' },
  title: { color: c.ink, fontSize: 24, fontWeight: '700' },
});
