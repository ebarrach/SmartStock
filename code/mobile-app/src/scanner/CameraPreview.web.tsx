import React from 'react';
import { Message } from '../ui/components';
export function CameraPreview(_props: {
  active: boolean;
  onCodes: (values: string[]) => void;
}) {
  return (
    <Message text="Le scanner caméra natif est disponible dans l’application Android. La cible web conserve les autres fonctionnalités." />
  );
}
